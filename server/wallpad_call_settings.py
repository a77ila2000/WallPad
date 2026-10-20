"""Persist desired settings; a wallpad acknowledgement confirms local storage."""
from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Any, Callable


class WallpadCallSettings:
    SOURCES = ("lobby0043", "lobby0042", "lobby0041")

    def __init__(self, path: Path, source_locations: dict[str, str]) -> None:
        self.path = Path(path)
        self.changed = threading.Condition(threading.RLock())
        self.revision = 1
        self.enabled = False
        self.quiet_hours = self.validate_quiet_hours({})
        self.source_ids = [key for key in self.SOURCES if key in source_locations]
        self.report: dict[str, Any] = {}
        if self.path.is_file():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                self.enabled = data.get("centralAutoUnlock") is True
                self.quiet_hours = self.validate_quiet_hours(data.get("quietHours", {}))
                self.revision = max(1, int(data.get("revision", 1)))
            except (OSError, ValueError, TypeError):
                # Restore OFF even if the server's saved settings are corrupt.
                self.enabled = False
                self.quiet_hours = self.validate_quiet_hours({})
                self.revision = int(time.time() * 1000)

    @staticmethod
    def validate_quiet_hours(value: Any) -> dict[str, Any]:
        if not isinstance(value, dict):
            raise ValueError("quietHours must be an object")
        enabled = value.get("enabled", False)
        if not isinstance(enabled, bool):
            raise ValueError("quietHours.enabled must be boolean")
        times = {}
        for key, default in (("start", "22:00"), ("end", "02:00")):
            raw = value.get(key, default)
            if (not isinstance(raw, str) or len(raw) != 5 or raw[2] != ":"
                    or not raw[:2].isdigit() or not raw[3:].isdigit()
                    or int(raw[:2]) > 23 or int(raw[3:]) > 59):
                raise ValueError("quietHours times must use HH:mm")
            times[key] = raw
        if times["start"] == times["end"]:
            raise ValueError("quietHours start and end must differ")
        # percent is the remaining signal amplitude, not the reduction amount.
        percent = value.get("percent", 50)
        if type(percent) is not int or percent not in (15, 50):
            raise ValueError("quietHours.percent must be 15 or 50")
        return {"enabled": enabled, **times, "percent": percent}

    def desired(self) -> dict[str, Any]:
        with self.changed:
            return {"centralAutoUnlock": self.enabled, "revision": self.revision,
                    "quietHours": dict(self.quiet_hours),
                    "sourceIds": self.source_ids}

    def snapshot(self) -> dict[str, Any]:
        with self.changed:
            age = time.time() - self.report.get("receivedEpoch", 0)
            fresh = 0 <= age <= 45 and self.report.get("revision") == self.revision
            quiet_synced = (self.report.get("quietHours") == self.quiet_hours
                            or (self.report.get("quietHours") is None
                                and self.quiet_hours == self.validate_quiet_hours({})))
            return {**self.desired(), "reportedEnabled": self.report.get("centralAutoUnlock"),
                    "wallpadVersion": self.report.get("version", ""),
                    "quietHoursSynced": fresh and quiet_synced,
                    "quietHoursReady": fresh and self.report.get("quietHoursReady") is True,
                    "quietHoursActive": fresh and self.report.get("quietHoursActive") is True,
                    "quietHoursError": self.report.get("quietHoursError", "") if fresh else "",
                    "synced": (fresh and quiet_synced
                               and self.report.get("centralAutoUnlock") == self.enabled)}

    def update(self, body: dict[str, Any], timeout: float = 8,
               on_changed: Callable[[], None] | None = None) -> dict[str, Any]:
        with self.changed:
            if not isinstance(body, dict) or not any(key in body for key in ("centralAutoUnlock", "quietHours")):
                raise ValueError("no call settings supplied")
            enabled = body.get("centralAutoUnlock", self.enabled)
            if not isinstance(enabled, bool):
                raise ValueError("centralAutoUnlock must be boolean")
            if enabled and len(self.source_ids) != 3:
                raise ValueError("three verified central entrance sources are required")
            quiet_hours = self.validate_quiet_hours(body.get("quietHours", self.quiet_hours))
            revision = max(self.revision + 1, int(time.time() * 1000))
            value = {"centralAutoUnlock": enabled, "revision": revision, "quietHours": quiet_hours}
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix(".tmp")
            with temporary.open("w", encoding="utf-8") as stream:
                json.dump(value, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, self.path)
            self.enabled, self.revision = enabled, revision
            self.quiet_hours = quiet_hours
        # Wake the bridge before waiting for its ACK. Do not hold this settings
        # lock while the callback takes the camera wait lock.
        if on_changed is not None:
            on_changed()
        with self.changed:
            deadline = time.monotonic() + max(0, timeout)
            while not self.snapshot()["synced"]:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.changed.wait(remaining)
            return self.snapshot()

    def acknowledge(self, body: dict[str, Any]) -> None:
        if not isinstance(body.get("centralAutoUnlock"), bool):
            raise ValueError("centralAutoUnlock must be boolean")
        revision = body.get("revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 0:
            raise ValueError("invalid revision")
        quiet_hours = self.validate_quiet_hours(body["quietHours"]) if "quietHours" in body else None
        with self.changed:
            self.report = {"revision": revision, "centralAutoUnlock": body["centralAutoUnlock"],
                           "version": str(body.get("version", ""))[:32], "receivedEpoch": time.time()}
            if "quietHours" in body:
                self.report.update(quietHours=quiet_hours,
                                   quietHoursReady=body.get("quietHoursReady") is True,
                                   quietHoursActive=body.get("quietHoursActive") is True,
                                   quietHoursError=str(body.get("quietHoursError", ""))[:160])
            self.changed.notify_all()
