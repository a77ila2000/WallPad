"""COMMAX intercom RS-485 protocol helpers.

The 16-byte 0x10 bell/status family was observed on this installation's
TV-PHONE bus on 2026-09-21.  The 0x11 command and 0x12 state labels still come
from user-supplied community references.  The module remains receive-only: it
validates and labels captured frames but exposes no network writer.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any


FRAME_SIZE = 16
START_BYTE = 0x02
END_BYTE = 0x03


def checksum(frame: bytes) -> int:
    """Return the reference checksum (bytes 1 through 13, modulo 256)."""
    return sum(frame[1:14]) & 0xFF


def is_valid_frame(frame: bytes) -> bool:
    return (
        len(frame) == FRAME_SIZE
        and frame[0] == START_BYTE
        and frame[-1] == END_BYTE
        and checksum(frame) == frame[14]
    )


def hex_frame(frame: bytes) -> str:
    return " ".join(f"{value:02X}" for value in frame)


@dataclass(frozen=True)
class DecodedIntercomFrame:
    kind: str
    raw: str
    values: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _location(value: int) -> str | None:
    return {0x30: "central_entrance", 0x40: "unit_entrance"}.get(value)


def decode_frame(frame: bytes) -> DecodedIntercomFrame:
    if not is_valid_frame(frame):
        raise ValueError("invalid COMMAX intercom reference frame")

    raw = hex_frame(frame)
    message_type = frame[1]
    action = frame[10]
    location = _location(frame[11])

    # 0x11 command frames from the supplied references.  These labels are
    # reference-derived until the same bytes are captured in this apartment.
    if message_type == 0x11 and location and frame[13] == 0x01:
        kind = {0x04: "call_start_command", 0x05: "door_open_command", 0x06: "call_end_command"}.get(action)
        if kind:
            return DecodedIntercomFrame(
                kind,
                raw,
                {"location": location, "evidence": "reference_only"},
            )

    # 0x12 state frames from the supplied references.
    if message_type == 0x12 and location and frame[13] == 0x01:
        state = {0x41: "on", 0x61: "off"}.get(action)
        if state:
            return DecodedIntercomFrame(
                "call_state",
                raw,
                {"location": location, "state": state, "evidence": "reference_only"},
            )

    # The 0x10 bell frames include site-specific address bytes.  The address
    # below was captured repeatedly on this installation's TV-PHONE bus.
    if message_type == 0x10:
        address_bytes = frame[2:10]
        observed_here = address_bytes == bytes.fromhex("01 01 02 03 01 01 02 03")
        return DecodedIntercomFrame(
            "bell_activity",
            raw,
            {
                "address": hex_frame(address_bytes),
                "household": "101-203" if observed_here else None,
                "action": action,
                "evidence": "observed_20260921" if observed_here else "reference_only",
            },
        )

    return DecodedIntercomFrame(
        "unknown",
        raw,
        {"messageType": message_type, "action": action, "evidence": "reference_only"},
    )


class IntercomFrameStream:
    """Recover checksum-valid 16-byte frames from arbitrary TCP chunks."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.discarded_bytes = 0

    def feed(self, data: bytes) -> list[bytes]:
        self._buffer.extend(data)
        frames: list[bytes] = []
        while len(self._buffer) >= FRAME_SIZE:
            if self._buffer[0] != START_BYTE:
                del self._buffer[0]
                self.discarded_bytes += 1
                continue
            candidate = bytes(self._buffer[:FRAME_SIZE])
            if is_valid_frame(candidate):
                frames.append(candidate)
                del self._buffer[:FRAME_SIZE]
            else:
                del self._buffer[0]
                self.discarded_bytes += 1
        return frames
