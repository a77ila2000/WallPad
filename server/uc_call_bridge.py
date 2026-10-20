"""Local COMMAX UC call receiver used for common-entrance unlock research.

The bridge supervises Baresip only after the official UC HTTP bootstrap has
completed.  Its unlock endpoint remains local and requires all of the
following: an active incoming lobby call, a matching passive visitor event,
the locally stored four-digit door PIN, and an ``openDoorReturn/OK`` response.
"""

from __future__ import annotations

import json
import os
import re
import socket
import subprocess
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from official_uc import build_open_door_monitoring, derive_uc_profile


LOBBY_ID = re.compile(r"sips?:([^@;>\s]+)@", re.IGNORECASE)


def encode_netstring(value: dict[str, Any]) -> bytes:
    body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return str(len(body)).encode("ascii") + b":" + body + b","


def extract_lobby_id(value: Any) -> str:
    text = str(value or "")
    match = LOBBY_ID.search(text)
    if match:
        return match.group(1)
    match = re.search(r"<?([A-Za-z0-9_.-]+)@", text)
    return match.group(1) if match else ""


class CallTracker:
    def __init__(self, allowed_lobbies: set[str]) -> None:
        self.allowed_lobbies = allowed_lobbies
        self.lock = threading.RLock()
        self.active: dict[str, Any] | None = None
        self.call_sequence = 0
        self.last_door_result: dict[str, Any] | None = None

    def handle_event(self, event: dict[str, Any]) -> None:
        event_type = str(event.get("type") or "").upper()
        if event_type == "CALL_INCOMING":
            source = extract_lobby_id(event.get("peeruri") or event.get("param"))
            if source not in self.allowed_lobbies:
                return
            with self.lock:
                self.call_sequence += 1
                self.active = {
                    "sourceId": source,
                    "callId": self.call_sequence,
                    "baresipId": str(event.get("id") or ""),
                    "startedAtEpoch": int(time.time()),
                }
        elif event_type in {"CALL_CLOSED", "CALL_REJECTED"}:
            with self.lock:
                active = self.active
                if active and (
                    not event.get("id") or str(event.get("id")) == active.get("baresipId")
                ):
                    self.active = None
        elif str(event.get("class") or "").lower() == "message":
            raw = json.dumps(event, ensure_ascii=False)
            if "openDoorReturn" in raw:
                with self.lock:
                    self.last_door_result = {
                        "ok": '"OK"' in raw or ":OK" in raw,
                        "receivedAtEpoch": int(time.time()),
                    }

    def matching_call(self, source_id: str, max_age: int = 60) -> dict[str, Any] | None:
        with self.lock:
            if not self.active or self.active.get("sourceId") != source_id:
                return None
            if int(time.time()) - int(self.active.get("startedAtEpoch", 0)) > max_age:
                return None
            return dict(self.active)


class BaresipControl:
    def __init__(self, host: str, port: int, tracker: CallTracker) -> None:
        self.host = host
        self.port = port
        self.tracker = tracker
        self.sock: socket.socket | None = None
        self.lock = threading.Lock()
        self.responses: dict[str, dict[str, Any]] = {}
        self.changed = threading.Condition(self.lock)
        self.stop_event = threading.Event()

    def connect(self) -> None:
        sock = socket.create_connection((self.host, self.port), timeout=3)
        sock.settimeout(1)
        self.sock = sock
        threading.Thread(target=self._reader, name="baresip-ctrl-reader", daemon=True).start()

    def _read_netstring(self) -> dict[str, Any] | None:
        assert self.sock is not None
        length = bytearray()
        while True:
            value = self.sock.recv(1)
            if not value:
                return None
            if value == b":":
                break
            if not value.isdigit() or len(length) > 9:
                raise ValueError("invalid Baresip netstring")
            length.extend(value)
        body = bytearray()
        remaining = int(length.decode("ascii"))
        while remaining:
            chunk = self.sock.recv(remaining)
            if not chunk:
                return None
            body.extend(chunk)
            remaining -= len(chunk)
        if self.sock.recv(1) != b",":
            raise ValueError("invalid Baresip netstring terminator")
        value = json.loads(body.decode("utf-8"))
        return value if isinstance(value, dict) else None

    def _reader(self) -> None:
        try:
            while not self.stop_event.is_set():
                try:
                    value = self._read_netstring()
                except socket.timeout:
                    continue
                if value is None:
                    return
                if value.get("event"):
                    self.tracker.handle_event(value)
                if value.get("response"):
                    token = str(value.get("token") or "")
                    with self.changed:
                        self.responses[token] = value
                        self.changed.notify_all()
        finally:
            self.stop_event.set()

    def command(self, command: str, params: str = "", timeout: float = 3.0) -> dict[str, Any]:
        token = uuid.uuid4().hex
        packet = {"command": command, "token": token}
        if params:
            packet["params"] = params
        with self.changed:
            if self.sock is None:
                raise ConnectionError("Baresip control is not connected")
            self.sock.sendall(encode_netstring(packet))
            deadline = time.monotonic() + timeout
            while token not in self.responses:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("Baresip command timed out")
                self.changed.wait(remaining)
            response = self.responses.pop(token)
        if response.get("ok") is not True:
            raise OSError("Baresip command failed")
        return response


class UcBridge:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.session_path = Path(config["sessionPath"])
        self.state_dir = Path(config["stateDir"])
        self.allowed_lobbies = list(config.get("allowedLobbies", []))
        self.tracker = CallTracker(set(self.allowed_lobbies))
        self.control: BaresipControl | None = None
        self.process: subprocess.Popen[str] | None = None
        self.lock = threading.RLock()
        self.last_error = "UC 계정 정보 대기 중"
        self.registered = False
        self.current_contact = 0
        self.stop_event = threading.Event()

    def _load_session(self) -> dict[str, Any]:
        return json.loads(self.session_path.read_text(encoding="utf-8"))

    def _write_baresip_files(self, session: dict[str, Any]) -> None:
        uc = session["uc"]
        profile = derive_uc_profile(session["residence"], session["deviceUuid"])
        sip_server = str(uc["sipServer"]).strip()
        self.state_dir.mkdir(parents=True, exist_ok=True)
        config = "\n".join(
            [
                "sip_listen 0.0.0.0:5159",
                "sip_transports tls",
                "sip_verify_server yes",
                "module_path /usr/lib/baresip/modules",
                "module_tmp account.so",
                "module_app menu.so",
                "module_app contact.so",
                "module_app ctrl_tcp.so",
                "module g711.so",
                "ctrl_tcp_listen 127.0.0.1:18992",
                "",
            ]
        )
        account = (
            f'<sip:{profile["id"]}@commax.com;transport=tls>'
            f';auth_user={profile["id"]};auth_pass={profile["password"]}'
            f';outbound="sip:{sip_server};transport=tls";sipnat=outbound'
            ';regint=600;answermode=manual;inreq_allowed=yes'
            ';audio_codecs=pcma,pcmu;video_codecs=\n'
        )
        contacts = "\n".join(
            f'"{lobby}" <sip:{lobby}@commax.com>' for lobby in self.allowed_lobbies
        ) + "\n"
        for name, text in (("config", config), ("accounts", account), ("contacts", contacts)):
            path = self.state_dir / name
            path.write_text(text, encoding="utf-8")
            os.chmod(path, 0o600)

    def _ready(self, session: dict[str, Any]) -> bool:
        uc = session.get("uc") if isinstance(session.get("uc"), dict) else {}
        return bool(uc.get("registered") and uc.get("sipServer"))

    def supervise(self) -> None:
        while not self.stop_event.wait(3):
            if self.process and self.process.poll() is None and self.control:
                continue
            try:
                session = self._load_session()
                if not self._ready(session):
                    self.last_error = "UC HTTP 등록 완료 대기 중"
                    continue
                self._write_baresip_files(session)
                self.process = subprocess.Popen(
                    ["/usr/bin/baresip", "-f", str(self.state_dir)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    text=True,
                )
                for _ in range(20):
                    if self.process.poll() is not None:
                        raise RuntimeError("Baresip exited before control connection")
                    try:
                        control = BaresipControl("127.0.0.1", 18992, self.tracker)
                        control.connect()
                        self.control = control
                        control.command("reginfo")
                        self.registered = True
                        self.last_error = ""
                        break
                    except (ConnectionError, OSError):
                        time.sleep(0.5)
                else:
                    raise TimeoutError("Baresip control did not start")
            except Exception as exc:
                self.registered = False
                self.control = None
                self.last_error = str(exc)
                if self.process and self.process.poll() is None:
                    self.process.terminate()

    def status(self) -> dict[str, Any]:
        with self.tracker.lock:
            active = dict(self.tracker.active) if self.tracker.active else None
        return {
            "serviceReady": bool(self.control and self.process and self.process.poll() is None),
            "registrationStarted": self.registered,
            "activeCall": active,
            "lastError": self.last_error,
            "unlockVerified": False,
        }

    def _select_contact(self, source_id: str) -> None:
        if source_id not in self.allowed_lobbies:
            raise ValueError("unknown lobby")
        target = self.allowed_lobbies.index(source_id)
        if not self.control:
            raise ConnectionError("Baresip control is not connected")
        while self.current_contact != target:
            self.control.command("contact_next")
            self.current_contact = (self.current_contact + 1) % len(self.allowed_lobbies)

    def unlock(self, event: dict[str, Any]) -> dict[str, Any]:
        source_id = str(event.get("sourceId") or "")
        active = self.tracker.matching_call(source_id)
        if not active:
            raise PermissionError("matching active common-entrance call is required")
        pin_path = Path(self.config["doorPinPath"])
        if not pin_path.is_file():
            raise PermissionError("common-entrance door PIN is not configured")
        door_pin = pin_path.read_text(encoding="utf-8").strip()
        self._select_contact(source_id)
        assert self.control is not None
        before = int(time.time())
        payload = build_open_door_monitoring(
            door_pin, source_id, int(active["callId"]), str(event.get("relay") or "")
        )
        self.control.command("message", payload)
        deadline = time.monotonic() + 3.0
        while time.monotonic() < deadline:
            with self.tracker.lock:
                result = self.tracker.last_door_result
                if result and result.get("receivedAtEpoch", 0) >= before:
                    if result.get("ok"):
                        return {"accepted": True, "source": "official_uc", "confirmed": True}
                    raise PermissionError("COMMAX door terminal rejected the request")
            time.sleep(0.05)
        raise TimeoutError("COMMAX openDoorReturn was not received")


class Handler(BaseHTTPRequestHandler):
    server: "BridgeServer"

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def _json(self, status: int, value: dict[str, Any]) -> None:
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        if self.path != "/status":
            self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        self._json(HTTPStatus.OK, self.server.bridge.status())

    def do_POST(self) -> None:
        if self.path != "/unlock":
            self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        if not self.server.authorized(self.headers.get("Authorization", "")):
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            length = min(65536, int(self.headers.get("Content-Length", "0")))
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            if body.get("action") != "unlock" or not isinstance(body.get("event"), dict):
                raise ValueError("invalid unlock request")
            result = self.server.bridge.unlock(body["event"])
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.CONFLICT, {"error": str(exc), "accepted": False})
        except (OSError, ValueError, TimeoutError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc), "accepted": False})


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], bridge: UcBridge, token_path: Path) -> None:
        super().__init__(address, Handler)
        self.bridge = bridge
        self.token_path = token_path

    def authorized(self, value: str) -> bool:
        if not self.token_path.is_file():
            return False
        return value == "Bearer " + self.token_path.read_text(encoding="utf-8").strip()


def main() -> None:
    config_path = Path(os.environ.get("HOMEPAD_UC_BRIDGE_CONFIG", "/opt/homepad-agent/uc-bridge.json"))
    config = json.loads(config_path.read_text(encoding="utf-8"))
    bridge = UcBridge(config)
    threading.Thread(target=bridge.supervise, name="uc-supervisor", daemon=True).start()
    server = BridgeServer(
        (str(config.get("listenHost", "127.0.0.1")), int(config.get("listenPort", 18991))),
        bridge,
        Path(config["bearerTokenPath"]),
    )
    try:
        server.serve_forever()
    finally:
        bridge.stop_event.set()
        if bridge.process and bridge.process.poll() is None:
            bridge.process.terminate()


if __name__ == "__main__":
    main()
