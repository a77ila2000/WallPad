#!/usr/bin/env python3
"""HomePad Companion local gateway.

Keeps the single EW11 TCP connection, decodes verified RS-485 state frames,
and serves a same-origin mobile web app. Control is disabled by default.
"""

from __future__ import annotations

import argparse
import base64
import itertools
import json
import mimetypes
import os
import queue
import re
import secrets
import socket
import threading
import time
import urllib.request
import uuid
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

from official_commax import OfficialCommaxClient
from official_apti import AptiParkingClient
from wallpad_call_settings import WallpadCallSettings

from protocol import (
    FrameStream,
    build_batch_switch_command,
    build_elevator_call_command,
    build_light_power_command,
    build_power_command,
    build_setpoint_command,
    decode_frame,
    hex_frame,
)


ROOT = Path(__file__).resolve().parent
STATIC_ROOT = ROOT / "static"
DEFAULT_CONFIG = ROOT / "config.json"
BASEMENT_FLOOR_LABEL = "B2"


def iso_now() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def elevator_floor_label(floor: int, basement: bool) -> str:
    """Return the building's user-facing elevator floor label.

    The controller reports basement position as a floor number plus a basement
    flag.  This building has B2 only (there is no B1), so the raw numeric value
    must not leak into the UI when that flag is set.
    """
    return BASEMENT_FLOOR_LABEL if basement else str(floor)


def receive_jpeg_frame(connection: socket.socket, max_bytes: int) -> bytes:
    """Read the first complete JPEG from a raw CCTV TCP stream."""
    return next(iter_jpeg_frames(connection, max_bytes))


def iter_jpeg_frames(connection: socket.socket, max_bytes: int):
    """Yield complete JPEGs while preserving bytes received after each frame."""
    buffer = bytearray()
    while True:
        start = buffer.find(b"\xff\xd8")
        if start < 0 and len(buffer) > 1:
            del buffer[:-1]
        elif start > 0:
            del buffer[:start]
        if start >= 0:
            end = buffer.find(b"\xff\xd9", 2)
            if end >= 0:
                frame = bytes(buffer[: end + 2])
                del buffer[: end + 2]
                yield frame
                continue
            if len(buffer) > max_bytes:
                raise ValueError("CCTV JPEG frame exceeded the configured size limit")
        chunk = connection.recv(65536)
        if not chunk:
            raise ConnectionError("CCTV stream closed before a JPEG frame arrived")
        buffer.extend(chunk)


class UnitDoorRelay:
    """Coordinates an on-demand wallpad camera with one or more viewers."""

    def __init__(self, request_seconds: float = 12.0) -> None:
        self.changed = threading.Condition(threading.RLock())
        self.request_seconds = max(2.0, float(request_seconds))
        self.requested_until = 0.0
        self.viewers = 0
        self.sequence = 0
        self.frame = b""
        self.frame_at = 0.0

    def acquire_view(self) -> None:
        with self.changed:
            self.viewers += 1
            self.changed.notify_all()

    def release_view(self) -> None:
        with self.changed:
            self.viewers = max(0, self.viewers - 1)
            self.changed.notify_all()

    def request_view(self, seconds: float | None = None) -> None:
        duration = self.request_seconds if seconds is None else max(2.0, float(seconds))
        with self.changed:
            self.requested_until = max(self.requested_until, time.monotonic() + duration)
            self.changed.notify_all()

    def desired(self) -> dict[str, Any]:
        with self.changed:
            remaining = max(0.0, self.requested_until - time.monotonic())
            age = None if not self.frame_at else max(0.0, time.monotonic() - self.frame_at)
            return {
                "active": self.viewers > 0 or remaining > 0,
                "remainingMs": int(remaining * 1000),
                "viewers": self.viewers,
                "sequence": self.sequence,
                "frameAgeMs": None if age is None else int(age * 1000),
            }

    def publish(self, frame: bytes, maximum: int) -> int:
        if len(frame) < 4 or len(frame) > maximum:
            raise ValueError("invalid unit-door JPEG size")
        if not frame.startswith(b"\xff\xd8") or not frame.endswith(b"\xff\xd9"):
            raise ValueError("unit-door JPEG required")
        with self.changed:
            self.frame = bytes(frame)
            self.frame_at = time.monotonic()
            self.sequence += 1
            self.changed.notify_all()
            return self.sequence

    def wait_frame(self, after: int, timeout: float) -> tuple[int, bytes] | None:
        deadline = time.monotonic() + max(0.0, float(timeout))
        with self.changed:
            while self.sequence <= after:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.changed.wait(remaining)
            return self.sequence, self.frame

    def latest_frame(self, max_age: float) -> tuple[int, bytes] | None:
        """Return a recent frame without waiting for the next capture.

        Reopening the unit-door view should show the latest trustworthy image
        immediately while the wallpad prepares the next live frame.
        """
        with self.changed:
            if not self.frame_at or not self.frame:
                return None
            if time.monotonic() - self.frame_at > max(0.0, float(max_age)):
                return None
            return self.sequence, self.frame

    def latest_frame_info(self, max_age: float) -> tuple[int, bytes, int] | None:
        """Return a recent frame and its age for a labelled quick preview."""
        with self.changed:
            if not self.frame_at or not self.frame:
                return None
            age = max(0.0, time.monotonic() - self.frame_at)
            if age > max(0.0, float(max_age)):
                return None
            return self.sequence, self.frame, int(age * 1000)


class VisitorAudioSession:
    """Small bounded PCMU exchange used by the wallpad and phone clients.

    Audio is kept only in memory and every queue is capped to roughly four
    seconds.  A slow or disconnected client therefore cannot grow the server
    process or replay an old conversation.
    """

    FRAME_BYTES = 160
    MAX_FRAMES = 200

    def __init__(self, event_id: str) -> None:
        self.event_id = event_id
        self.created_at = time.time()
        self.last_activity = self.created_at
        self.active = True
        self.wallpad_ready = False
        self.phone_ready = False
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.visitor_sequence = 0
        self.phone_sequence = 0
        self.visitor_frames: deque[tuple[int, bytes]] = deque(maxlen=self.MAX_FRAMES)
        self.phone_frames: deque[tuple[int, bytes]] = deque(maxlen=self.MAX_FRAMES)

    @classmethod
    def decode_frames(cls, encoded: str) -> list[bytes]:
        try:
            raw = base64.b64decode(str(encoded or ""), validate=True)
        except (ValueError, TypeError) as exc:
            raise ValueError("invalid PCMU audio payload") from exc
        if not raw or len(raw) > cls.FRAME_BYTES * 25 or len(raw) % cls.FRAME_BYTES:
            raise ValueError("PCMU audio must contain 20 ms frames")
        return [raw[offset:offset + cls.FRAME_BYTES]
                for offset in range(0, len(raw), cls.FRAME_BYTES)]

    @staticmethod
    def encode_frames(frames: list[bytes]) -> str:
        return base64.b64encode(b"".join(frames)).decode("ascii")

    def push(self, direction: str, frames: list[bytes]) -> int:
        with self.changed:
            if not self.active:
                raise ValueError("visitor audio session is not active")
            if direction == "visitor":
                queue_ref = self.visitor_frames
                for frame in frames:
                    self.visitor_sequence += 1
                    queue_ref.append((self.visitor_sequence, frame))
                sequence = self.visitor_sequence
                self.wallpad_ready = True
            elif direction == "phone":
                queue_ref = self.phone_frames
                for frame in frames:
                    self.phone_sequence += 1
                    queue_ref.append((self.phone_sequence, frame))
                sequence = self.phone_sequence
                self.phone_ready = True
            else:
                raise ValueError("invalid visitor audio direction")
            self.last_activity = time.time()
            self.changed.notify_all()
            return sequence

    def read(self, direction: str, after: int, timeout: float) -> tuple[int, list[bytes]]:
        deadline = time.monotonic() + max(0.0, min(float(timeout), 20.0))
        with self.changed:
            while self.active:
                source = self.visitor_frames if direction == "visitor" else self.phone_frames
                values = [(sequence, frame) for sequence, frame in source if sequence > after]
                if values:
                    selected = values[:25]
                    self.last_activity = time.time()
                    return selected[-1][0], [frame for _, frame in selected]
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.changed.wait(remaining)
            sequence = self.visitor_sequence if direction == "visitor" else self.phone_sequence
            return sequence, []

    def close(self) -> None:
        with self.changed:
            self.active = False
            self.changed.notify_all()


class StateStore:
    def __init__(self, config: dict[str, Any]) -> None:
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.sequence = 0
        self._snapshot_cache_sequence = -1
        self._snapshot_cache_json = ""
        self.started_at = iso_now()
        self.zone_names = {int(key): value for key, value in config.get("zones", {}).items()}
        self.control = config.get("control", {})
        self.light_config = config.get("lights", {})
        self.batch_switch_config = config.get("batchSwitch", {})
        self.integration_config = config.get("integration", {})
        self.official_config = config.get("officialCommax", {})
        self.light_names = {
            int(key): value for key, value in self.light_config.get("names", {}).items()
        }
        self.elevator_config = config.get("elevator", {})
        self.elevator_home_floor = int(self.elevator_config.get("homeFloor", 2))
        self.visitor_config = config.get("visitors", {})
        self.cctv_config = config.get("cctv", {})
        self.door_unlock_config = config.get("doorUnlock", {})
        wallpad_bridge_ready = bool(config.get("wallpadBridge", {}).get("enabled", False))
        # Audio may be suspended independently during wallpad recovery while
        # the existing photo and native call/door control routes stay usable.
        wallpad_bridge_config = config.get("wallpadBridge", {})
        wallpad_audio_master = wallpad_bridge_ready and bool(
            wallpad_bridge_config.get("audioEnabled", True)
        )
        wallpad_central_audio_ready = wallpad_audio_master and bool(
            wallpad_bridge_config.get("centralAudioEnabled", True)
        )
        wallpad_unit_audio_ready = wallpad_audio_master and bool(
            wallpad_bridge_config.get("unitAudioEnabled", True)
        )
        wallpad_audio_ready = wallpad_central_audio_ready or wallpad_unit_audio_ready
        central_unlock = self.door_unlock_config.get("central", {})
        unit_unlock = self.door_unlock_config.get("unit", {})
        central_unlock_ready = bool(
            central_unlock.get("enabled")
            and (
                (central_unlock.get("transport") == "http" and central_unlock.get("endpointUrl"))
                or central_unlock.get("transport") == "wallpad_queue"
            )
        )
        central_verified_locations = central_unlock.get("verifiedLocations", [])
        central_unlock_verified = bool(
            central_unlock_ready
            and isinstance(central_verified_locations, list)
            and central_verified_locations
        )
        unit_unlock_ready = bool(
            unit_unlock.get("enabled")
            and (
                (unit_unlock.get("transport") == "http" and unit_unlock.get("endpointUrl"))
                or unit_unlock.get("transport") == "wallpad_queue"
            )
        )
        unit_verified_locations = unit_unlock.get("verifiedLocations", [])
        unit_unlock_verified = bool(
            unit_unlock_ready
            and isinstance(unit_verified_locations, list)
            and unit_verified_locations
        )
        elevator_realtime_ready = bool(
            self.elevator_config.get("realtimeWhenWallpadOff", False)
        )
        self.events: deque[dict[str, Any]] = deque(maxlen=200)
        self.cctv_motion_ids: deque[str] = deque(maxlen=128)
        self.elevator_sources: dict[str, dict[str, Any]] = {}
        self.elevator_last_floor: int | None = None
        self.elevator_last_change = time.monotonic()
        self.elevator_direction = 0
        self.state: dict[str, Any] = {
            "service": {"startedAt": self.started_at, "version": "0.14.31"},
            "integration": {
                "policy": self.integration_config.get("policy", "official_first"),
                "priority": self.integration_config.get(
                    "priority", ["official_app", "local_capture"]
                ),
                "officialSession": {
                    "available": False,
                    "authenticated": False,
                    "reason": "공식 계정 세션과 장치 목록을 아직 연결하지 않음",
                },
                "officialState": {
                    "enabled": bool(self.official_config.get("statePollingEnabled", False)),
                    "available": False,
                    "lastUpdatedAt": None,
                    "deviceCount": 0,
                    "error": None,
                },
                "featureRoutes": {
                    "lights": "local_capture",
                    "heating": "local_capture",
                    "batchSwitch": "local_capture",
                    "ventilation": "local_capture",
                    "cctv": "local_capture" if self.cctv_config.get("enabled", False) else "pending_official_validation",
                    "push": "pending_official_validation",
                    "visitorPhotos": "wallpad_capture_bridge",
                    "callsAndUnlock": "wallpad_call_detection",
                    "elevator": "local_capture",
                    "elevatorCall": (
                        "ethernet_command"
                        if self.elevator_config.get("callTransport") == "ethernet_http"
                        else "ext2_ew11"
                    ),
                },
                "controlFallback": "explicit_failure_only",
            },
            "ew11": {
                "connected": False,
                "host": config["ew11"]["host"],
                "port": config["ew11"]["port"],
                "lastFrameAt": None,
                "validFrames": 0,
                "discardedBytes": 0,
                "error": None,
            },
            "heating": {"zones": {}},
            "lights": {},
            "batchSwitch": {"available": False, "enabled": None},
            "ventilation": {"available": False},
            "elevator": {"available": False, "fresh": False},
            "elevatorCall": {
                "status": "idle",
                "target": None,
                "lastRequestedAt": None,
                "lastRequestedEpoch": 0,
                "lastSentAt": None,
                "lastConfirmedAt": None,
                "availableAtEpoch": 0,
            },
            "visitors": {
                "enabled": bool(self.visitor_config.get("enabled", False)),
                "ringing": False,
                "lastEvent": None,
                "history": [],
            },
            "parkingActivity": {
                "available": False,
                "lastUpdatedAt": None,
                "recent": [],
                "error": None,
            },
            "cctv": {
                "available": bool(self.cctv_config.get("enabled", False)),
                "source": "homenet-mjpeg-fallback",
                "channels": self.cctv_config.get("channels", []),
            },
            "capabilities": {
                "heatingRead": True,
                "heatingControl": bool(self.control.get("enabled", False)),
                "lightRead": True,
                "lightControl": bool(self.light_config.get("enabled", False)),
                "batchSwitchControl": bool(self.batch_switch_config.get("enabled", False)),
                "ventilationRead": False,
                "elevatorRead": bool(config.get("elevator", {}).get("statusUrl")),
                "elevatorCall": bool(self.elevator_config.get("callEnabled", False)),
                "elevatorHomeCall": bool(self.elevator_config.get("callEnabled", False)),
                "elevatorRealtimeWhenWallpadOff": elevator_realtime_ready,
                "elevatorDoorState": bool(config.get("elevator", {}).get("statusUrl")),
                "elevatorDestinationRead": False,
                "elevatorB2Call": bool(self.elevator_config.get("b2CallEnabled", False)),
                "visitorCalls": bool(self.visitor_config.get("enabled", False)),
                "visitorCallControl": central_unlock_ready or unit_unlock_ready,
                "visitorPhotos": wallpad_bridge_ready,
                "visitorAudio": wallpad_audio_ready,
                "visitorCentralAudio": wallpad_central_audio_ready,
                "visitorUnitAudio": wallpad_unit_audio_ready,
                "cctvRead": bool(self.cctv_config.get("enabled", False)),
                "doorUnlock": central_unlock_ready or unit_unlock_ready,
                "centralDoorUnlock": central_unlock_ready,
                "unitDoorUnlock": unit_unlock_ready,
            },
            "capabilityDetails": {
                "elevatorRealtimeWhenWallpadOff": {
                    "status": "verified" if elevator_realtime_ready else "unverified",
                    "reason": (
                        "R6850이 월패드 식별 주소로 읽기 전용 getEvStatus를 대신 보내고 응답을 월패드에 전달하지 않음"
                        if elevator_realtime_ready
                        else "월패드 화면이 꺼지면 getEvStatus와 EXT2 층수 프레임이 함께 멈춤"
                    ),
                    "required": (
                        None
                        if elevator_realtime_ready
                        else "월패드 내부 조회 실행 또는 서버에 등록된 별도 조회 클라이언트"
                    ),
                },
                "elevatorDoorState": {
                    "status": "observed_signature" if config.get("elevator", {}).get("statusUrl") else "unavailable",
                    "reason": "보관된 실측 응답에서 호출층 도착 시 evStatus가 약 1~2초 동안 1→9→1로 변하고 호출 플래그가 해제됨" if config.get("elevator", {}).get("statusUrl") else "엘리베이터 상태 조회 경로가 없음",
                    "required": "다음 실제 우리 층 도착에서 evStatus=9와 문 열림을 동시에 재확인" if config.get("elevator", {}).get("statusUrl") else "엘리베이터 상태 조회 연결",
                },
                "elevatorDestinationRead": {
                    "status": "unavailable",
                    "reason": "월패드 getEvStatus는 현재층·지하·방향·상태·상하 호출의 6개 필드만 제공하며 공식 elevatorCallLS도 결과만 반환하는 명령형 API임",
                    "required": "엘리베이터 제어기 또는 단지 관제의 목적층 상태 API·상류 패킷",
                },
                "elevatorB2Call": {
                    "status": "verified" if self.elevator_config.get("b2CallEnabled", False) else "unverified",
                    "reason": (
                        "B2 전용 호출 경로가 설정됨"
                        if self.elevator_config.get("b2CallEnabled", False)
                        else "공식 월패드의 callUp/callDown은 세대층 방향 호출이고 공식 elevatorCallLS에도 B2·목적층 인수가 없음"
                    ),
                    "required": (
                        None
                        if self.elevator_config.get("b2CallEnabled", False)
                        else "B2 공동현관·출입통제 단말의 정상 호출 패킷 또는 공식 인증 API"
                    ),
                },
                "visitorCalls": {
                    "status": "observed_signature" if self.visitor_config.get("enabled", False) else "disabled",
                    "reason": "호출 직후 월패드가 Ruvie TLS 세션에서 보내는 885-byte payload를 R6850에서 감지",
                    "confirmation": "호출 종료 후 단지 서버 callLog(place/state/time/image)로 보강",
                },
                "visitorCallControl": {
                    "status": "ready_for_field_test" if central_unlock_ready else "disabled",
                    "reason": "월패드 SIP 통화 화면의 수락·종료 버튼 제어 경로가 연결됐지만 활성 호출에서 아직 실기 검증하지 않음" if central_unlock_ready else "월패드 통화 명령 경로가 연결되지 않음",
                    "required": "공동현관 활성 호출 1회에서 수락·종료 동작 확인" if central_unlock_ready else "월패드 브리지 연결",
                },
                "visitorPhotos": {
                    "status": "server_verified" if central_unlock_verified else ("ready_for_field_test" if wallpad_bridge_ready else "disabled"),
                    "reason": "1층 공동현관 호출 사진을 월패드에서 640x480 JPEG로 캡처해 서버 호출 기록에 연결함" if central_unlock_verified else ("월패드 화면 캡처와 서버 업로드 경로가 연결됐지만 실제 호출 사진 수신은 아직 실기 검증하지 않음" if wallpad_bridge_ready else "월패드 사진 캡처 브리지가 연결되지 않음"),
                    "required": "휴대폰 큰 사진 알림 표시 확인" if central_unlock_verified else ("공동현관 또는 세대현관 활성 호출 1회에서 사진 업로드와 휴대폰 표시 확인" if wallpad_bridge_ready else "월패드 브리지 연결"),
                },
                "visitorAudio": {
                    "status": "ready_for_field_test" if wallpad_audio_ready else "disabled",
                    "reason": (
                        "실측 PCMU RTP를 월패드 로컬 프록시와 인증된 HTTPS 오디오 세션으로 중계하도록 연결함"
                        if wallpad_audio_ready
                        else "휴대폰 음성 중계가 중지되어 있습니다. 월패드 기본 통화를 사용해 주세요"
                    ),
                    "required": (
                        "활성 공동현관 호출에서 휴대폰 수신 음성과 마이크 송신을 각각 확인"
                        if wallpad_audio_ready
                        else "월패드 브리지 연결"
                    ),
                },
                "visitorCentralAudio": {
                    "status": "ready_for_field_test" if wallpad_central_audio_ready else "disabled",
                    "reason": "공동현관 PCMU RTP를 인증된 휴대폰 오디오 세션으로 중계함" if wallpad_central_audio_ready else "공동현관 휴대폰 음성 중계가 중지되어 있습니다. 월패드 기본 통화를 사용해 주세요",
                    "required": "활성 공동현관 호출에서 양방향 음성 확인" if wallpad_central_audio_ready else "월패드 공동현관 오디오 중계 점검",
                },
                "visitorUnitAudio": {
                    "status": "ready_for_field_test" if wallpad_unit_audio_ready else "disabled",
                    "reason": "세대현관 경로 32를 통화 중에만 네이티브 경로 34와 로컬 PCMU RTP로 중계함" if wallpad_unit_audio_ready else "세대현관 휴대폰 음성 중계가 중지되어 있습니다. 월패드 기본 통화를 사용해 주세요",
                    "required": "활성 세대현관 호출에서 수신·송신 프레임 수와 실제 양방향 음성을 확인" if wallpad_unit_audio_ready else "월패드 세대현관 오디오 중계 점검",
                },
                "doorUnlock": {
                    "status": "verified" if central_unlock_verified else ("ready_for_field_test" if central_unlock_ready or unit_unlock_ready else "protocol_observed"),
                    "reason": "1층 공동현관 활성 호출에서 월패드 기본 문열림 동작과 실제 문 개방·통화 종료를 확인함" if central_unlock_verified else ("문열림 명령 경로가 연결됐지만 실제 출입문 개방은 아직 실기 검증하지 않음" if central_unlock_ready or unit_unlock_ready else "29752/TLS 문열림 경로와 요청·응답 크기는 확인했지만 암호화된 평문 명령은 아직 미확정"),
                    "required": None if central_unlock_verified else ("활성 호출 1회에서 실제 문 개방 확인" if central_unlock_ready or unit_unlock_ready else "월패드 구성요소에서 평문 요청 또는 인증된 호출 방법 확인"),
                },
                "centralDoorUnlock": {
                    "status": "verified_one_location" if central_unlock_verified else ("ready_for_field_test" if central_unlock_ready else "protocol_observed"),
                    "reason": ("월패드 기본 문열림 버튼 경로로 실제 개방 확인: " + ", ".join(str(location) for location in central_verified_locations)) if central_unlock_verified else ("활성 호출을 월패드 통화 화면에서 문열림으로 처리하는 경로가 연결됐지만 실제 문 개방은 아직 실기 검증하지 않음" if central_unlock_ready else "중앙현관 29752/TLS 경로는 확인했지만 암호화된 평문 명령은 아직 미확정"),
                    "required": "지하 중앙현관 안쪽·바깥쪽 호출에서 같은 동작을 각각 확인" if central_unlock_verified else ("1층 또는 지하 중앙현관 활성 호출 1회에서 실제 문 개방 확인" if central_unlock_ready else "활성 중앙현관 호출에서 인증된 평문 요청 확인"),
                },
                "unitDoorUnlock": {
                    "status": "verified" if unit_unlock_verified else ("ready_for_field_test" if unit_unlock_ready else "local_wireless_path"),
                    "reason": ("월패드 기본 문열림 키와 같은 로컬 RF 경로로 실제 개방 확인" if unit_unlock_verified else ("활성 세대현관 호출에서 코맥스 기본 문열림 키와 같은 로컬 RF 경로를 실행하도록 연결함" if unit_unlock_ready else "세대 현관 문열림은 월패드 후면 송신기가 도어락으로 직접 보내는 로컬 무선 신호이며 Ethernet과 EXT2에는 명령이 나타나지 않음")),
                    "required": None if unit_unlock_verified else ("활성 세대현관 호출 1회에서 실제 문 개방과 통화 종료 확인" if unit_unlock_ready else "후면 송신기 입력의 전기 특성 확인 후 절연 접점 연결 또는 별도로 검증된 로컬 제어 입력"),
                },
            },
        }
        self._load_visitor_history()

    def _publish(self, event_type: str, data: dict[str, Any]) -> None:
        self.sequence += 1
        self._snapshot_cache_sequence = -1
        self._snapshot_cache_json = ""
        self.events.append(
            {"sequence": self.sequence, "type": event_type, "at": iso_now(), "data": data}
        )
        self.changed.notify_all()

    def set_ew11(self, *, connected: bool, error: str | None = None) -> None:
        with self.lock:
            current = self.state["ew11"]
            if current["connected"] == connected and current["error"] == error:
                return
            current["connected"] = connected
            current["error"] = error
            self._publish("ew11.connection", {"connected": connected, "error": error})

    def set_parking_activity(
        self, entries: list[dict[str, Any]], error: str | None = None
    ) -> None:
        with self.lock:
            current = self.state["parkingActivity"]
            previous_ids = [str(item.get("id") or "") for item in current.get("recent", [])]
            next_entries = entries[:10]
            next_ids = [str(item.get("id") or "") for item in next_entries]
            changed = previous_ids != next_ids or current.get("error") != error
            current.update(
                {
                    "available": error is None,
                    "lastUpdatedAt": iso_now() if error is None else current.get("lastUpdatedAt"),
                    "recent": next_entries,
                    "error": error,
                }
            )
            if changed:
                self._publish(
                    "parking.activity",
                    {"count": len(next_entries), "available": error is None, "error": error},
                )

    def publish_parking_vehicle(self, entry: dict[str, Any]) -> None:
        with self.lock:
            self._publish("parking.vehicle", entry)

    def record_cctv_motion(self, payload: dict[str, Any]) -> bool:
        camera = payload.get("cameraId")
        event_id = payload.get("id")
        detected = payload.get("detectedEpoch")
        if camera not in ("cam1", "cam2"):
            raise ValueError("unknown CCTV camera")
        if not isinstance(event_id, str) or not re.fullmatch(r"[a-f0-9]{32}", event_id):
            raise ValueError("invalid motion event id")
        if type(detected) not in (int, float) or not abs(time.time() - detected) <= 120:
            raise ValueError("motion event is not current")
        with self.lock:
            if event_id in self.cctv_motion_ids:
                return False
            self.cctv_motion_ids.append(event_id)
            self._publish("cctv.motion", {
                "id": event_id, "cameraId": camera,
                "detectedEpoch": int(detected), "test": payload.get("test") is True,
            })
        return True

    def _official_feature_fresh_locked(self, feature: str) -> bool:
        health = self.state["integration"]["officialState"]
        updated_epoch = int(health.get("lastUpdatedEpoch") or 0)
        max_age = max(15, int(float(self.official_config.get("pollSeconds", 5))) * 3)
        return (
            health.get("available") is True
            and self.state["integration"]["featureRoutes"].get(feature) == "official_api"
            and updated_epoch > 0
            and int(time.time()) - updated_epoch <= max_age
        )

    @staticmethod
    def _official_integer(item: dict[str, Any] | None) -> int | None:
        if not item:
            return None
        try:
            return int(round(float(item.get("value"))))
        except (TypeError, ValueError):
            return None

    def set_official_devices(self, devices: list[dict[str, Any]]) -> None:
        """Replace EXT2-derived room state with a fresh official inventory read."""
        index: dict[tuple[str, str, str], dict[str, Any]] = {}
        for item in devices:
            key = (
                str(item.get("nickname") or ""),
                str(item.get("rootDevice") or ""),
                str(item.get("sort") or ""),
            )
            if all(key):
                index[key] = item

        now_epoch = int(time.time())
        updated_at = iso_now()
        routes: set[str] = set()
        with self.lock:
            light_names = self.official_config.get("lightNames", {})
            for channel_text, nickname in light_names.items():
                item = index.get((str(nickname), "switch", "switchBinary"))
                if item is None:
                    continue
                channel = int(channel_text)
                value = {
                    "channel": channel,
                    "name": self.light_names.get(channel, str(nickname)),
                    "enabled": str(item.get("value", "")).lower() == "on",
                    "updatedAt": updated_at,
                    "source": "official_api",
                    "controls": self.allowed_light_controls(channel),
                }
                previous = self.state["lights"].get(channel_text)
                self.state["lights"][channel_text] = value
                if previous is None or previous.get("enabled") != value["enabled"]:
                    self._publish("light.channel", value)
                routes.add("lights")

            heating_names = self.official_config.get("heatingNames", {})
            for zone_text, nickname in heating_names.items():
                zone_id = int(zone_text)
                mode = index.get((str(nickname), "thermostat", "thermostatMode"))
                current = index.get((str(nickname), "thermostat", "airTemperature"))
                setpoint = index.get((str(nickname), "thermostat", "thermostatSetpoint"))
                if mode is None or current is None or setpoint is None:
                    continue
                enabled = str(mode.get("value", "")).lower() == "heat"
                value = {
                    "id": zone_id,
                    "name": self.zone_names.get(zone_id, str(nickname)),
                    "enabled": enabled,
                    "mode": 1 if enabled else 0,
                    "currentTemperature": self._official_integer(current),
                    "setpoint": self._official_integer(setpoint),
                    "updatedAt": updated_at,
                    "source": "official_api",
                    "controls": self.allowed_controls(zone_id),
                }
                previous = self.state["heating"]["zones"].get(zone_text)
                self.state["heating"]["zones"][zone_text] = value
                comparable = ("enabled", "mode", "currentTemperature", "setpoint")
                if previous is None or any(previous.get(name) != value.get(name) for name in comparable):
                    self._publish("heating.zone", value)
                routes.add("heating")

            batch_nickname = str(self.official_config.get("batchNickname", "")).strip()
            batch = index.get((batch_nickname, "switch", "switchBinary")) if batch_nickname else None
            if batch is not None:
                value = {
                    "available": True,
                    "enabled": str(batch.get("value", "")).lower() == "on",
                    "updatedAt": updated_at,
                    "source": "official_api",
                }
                previous = self.state["batchSwitch"]
                self.state["batchSwitch"] = value
                if previous.get("enabled") != value["enabled"]:
                    self._publish("batch_switch.state", value)
                routes.add("batchSwitch")

            ventilation_nickname = str(
                self.official_config.get("ventilationNickname", "환기 01")
            ).strip()
            vent_power = index.get((ventilation_nickname, "switch", "switchBinary"))
            if vent_power is not None:
                speed = index.get((ventilation_nickname, "switch", "fanSpeed"))
                reservation = index.get((ventilation_nickname, "switch", "reservation"))
                value = {
                    "available": True,
                    "enabled": str(vent_power.get("value", "")).lower() == "on",
                    "fanSpeed": speed.get("value") if speed else None,
                    "reservation": reservation.get("value") if reservation else None,
                    "updatedAt": updated_at,
                    "source": "official_api",
                }
                previous = self.state["ventilation"]
                self.state["ventilation"] = value
                if any(previous.get(name) != value.get(name) for name in ("enabled", "fanSpeed", "reservation")):
                    self._publish("ventilation.state", value)
                self.state["capabilities"]["ventilationRead"] = True
                routes.add("ventilation")

            for feature in routes:
                self.state["integration"]["featureRoutes"][feature] = "official_api"
            health = self.state["integration"]["officialState"]
            previous_health = (health.get("available"), health.get("error"))
            health.update(
                {
                    "enabled": True,
                    "available": True,
                    "lastUpdatedAt": updated_at,
                    "lastUpdatedEpoch": now_epoch,
                    "deviceCount": len(devices),
                    "error": None,
                }
            )
            if previous_health != (True, None):
                self._publish("official.state", dict(health))

    def set_official_state_error(self, error: str) -> None:
        with self.lock:
            health = self.state["integration"]["officialState"]
            error = str(error)[:256]
            if health.get("available") is False and health.get("error") == error:
                return
            health["available"] = False
            health["error"] = error
            self._publish("official.state", dict(health))

    def apply_frame(self, frame: bytes, discarded_bytes: int):
        decoded = decode_frame(frame)
        with self.lock:
            ew11 = self.state["ew11"]
            ew11["lastFrameAt"] = iso_now()
            ew11["validFrames"] += 1
            ew11["discardedBytes"] = discarded_bytes

            if decoded.kind in ("thermostat_status", "thermostat_ack"):
                if self._official_feature_fresh_locked("heating"):
                    return decoded
                values = decoded.values
                zone_id = values["zone"]
                key = str(zone_id)
                previous = self.state["heating"]["zones"].get(key)
                zone = {
                    "id": zone_id,
                    "name": self.zone_names.get(zone_id, f"구역 {zone_id}"),
                    "enabled": values["enabled"],
                    "mode": values["mode"],
                    "currentTemperature": values["currentTemperature"],
                    "setpoint": values["setpoint"],
                    "updatedAt": iso_now(),
                    "source": decoded.kind,
                    "raw": decoded.raw,
                    "controls": self.allowed_controls(zone_id),
                }
                self.state["heating"]["zones"][key] = zone
                comparable = ("enabled", "mode", "currentTemperature", "setpoint")
                if previous is None or any(previous.get(name) != zone.get(name) for name in comparable):
                    self._publish("heating.zone", zone)
                return decoded

            if decoded.kind in ("light_status", "light_ack"):
                if self._official_feature_fresh_locked("lights"):
                    return decoded
                channel = str(decoded.values["channel"])
                value = {
                    "channel": decoded.values["channel"],
                    "name": self.light_names.get(decoded.values["channel"], f"전등 {channel}"),
                    "enabled": decoded.values["enabled"],
                    "updatedAt": iso_now(),
                    "source": decoded.kind,
                    "raw": decoded.raw,
                    "controls": self.allowed_light_controls(decoded.values["channel"]),
                }
                if self.state["lights"].get(channel, {}).get("enabled") != value["enabled"]:
                    self.state["lights"][channel] = value
                    self._publish("light.channel", value)
                return decoded

            if decoded.kind in ("batch_switch_status", "batch_switch_command"):
                if self._official_feature_fresh_locked("batchSwitch"):
                    return decoded
                value = {
                    "available": True,
                    "enabled": decoded.values["enabled"],
                    "updatedAt": iso_now(),
                    "source": decoded.kind,
                    "raw": decoded.raw,
                }
                previous = self.state["batchSwitch"]
                self.state["batchSwitch"] = value
                if previous.get("enabled") != value["enabled"]:
                    self._publish("batch_switch.state", value)
                return decoded

            if decoded.kind == "ventilation_status":
                if self._official_feature_fresh_locked("ventilation"):
                    return decoded
                value = {
                    "available": True,
                    "enabled": decoded.values["enabled"],
                    "mode": decoded.values["mode"],
                    "updatedAt": iso_now(),
                }
                previous = self.state["ventilation"]
                self.state["ventilation"] = value
                if previous.get("enabled") != value["enabled"]:
                    self._publish("ventilation.state", value)
                return decoded

            if decoded.kind == "elevator_floor_status":
                floor = int(decoded.values["floor"])
                now_monotonic = time.monotonic()
                if self.elevator_last_floor is not None:
                    if floor > self.elevator_last_floor:
                        self.elevator_direction = 1
                        self.elevator_last_change = now_monotonic
                    elif floor < self.elevator_last_floor:
                        self.elevator_direction = 2
                        self.elevator_last_change = now_monotonic
                    elif now_monotonic - self.elevator_last_change >= 2.5:
                        self.elevator_direction = 0
                else:
                    self.elevator_last_change = now_monotonic
                self.elevator_last_floor = floor
                direction_text = {0: "정지", 1: "상승", 2: "하강"}[self.elevator_direction]
                direction_symbol = {0: "●", 1: "▲", 2: "▼"}[self.elevator_direction]
                self.elevator_sources["rs485"] = {
                    "available": True,
                    "fresh": True,
                    "floor": floor,
                    "isBasement": False,
                    "displayFloor": str(floor),
                    "direction": self.elevator_direction,
                    "directionText": direction_text,
                    "directionSymbol": direction_symbol,
                    "updatedEpoch": int(time.time()),
                    "updated": iso_now(),
                    "ageSeconds": 0,
                    "source": "ext2-rs485",
                    "raw": decoded.raw,
                }
                self.state["capabilities"]["elevatorRead"] = True
                self._refresh_elevator_locked()
            return decoded

    def allowed_controls(self, zone: int) -> list[str]:
        verified = self.control.get("verified", {})
        return list(verified.get(str(zone), [])) if self.control.get("enabled", False) else []

    def allowed_light_controls(self, channel: int) -> list[str]:
        verified = self.light_config.get("verified", {})
        return (
            list(verified.get(str(channel), []))
            if self.light_config.get("enabled", False)
            else []
        )

    def set_elevator(self, value: dict[str, Any]) -> None:
        with self.lock:
            normalized = dict(value)
            floor = normalized.get("floor", normalized.get("carFloor"))
            if floor is not None:
                try:
                    floor = int(floor)
                    basement = bool(int(normalized.get("isBasement", 0)))
                    normalized["floor"] = floor
                    normalized["isBasement"] = basement
                    normalized["displayFloor"] = elevator_floor_label(floor, basement)
                except (TypeError, ValueError):
                    pass
            self.elevator_sources["ethernet"] = normalized
            self._refresh_elevator_locked()

    def _refresh_elevator_locked(self) -> None:
        now_epoch = int(time.time())
        rs485 = dict(self.elevator_sources.get("rs485", {}))
        ethernet = dict(self.elevator_sources.get("ethernet", {}))

        if rs485:
            updated_epoch = int(rs485.get("updatedEpoch", 0))
            age = max(0, now_epoch - updated_epoch) if updated_epoch else None
            rs485["ageSeconds"] = age
            rs485["fresh"] = age is not None and age <= 5

        if rs485.get("fresh"):
            combined = rs485
            for name in ("callUp", "callDown", "evStatus"):
                if name in ethernet:
                    combined[name] = ethernet[name]
            # Observed EXT2 0x23 frames expose a floor number but no proven
            # basement bit. Use the fresh Ethernet flag when both sources name
            # the same floor. This building has B2 only, so any confirmed
            # basement position is rendered as B2 regardless of the raw number.
            if (
                ethernet.get("fresh")
                and ethernet.get("floor") == rs485.get("floor")
                and "isBasement" in ethernet
            ):
                basement = bool(ethernet["isBasement"])
                combined["isBasement"] = basement
                combined["displayFloor"] = elevator_floor_label(
                    int(combined["floor"]), basement
                )
            combined["ethernetFresh"] = bool(ethernet.get("fresh"))
        elif ethernet.get("fresh"):
            combined = ethernet
            combined["rs485Fresh"] = False
        elif rs485.get("available"):
            combined = rs485
            combined["ethernetFresh"] = False
        elif ethernet:
            combined = ethernet
        else:
            combined = {"available": False, "fresh": False}

        if "evStatus" in combined:
            try:
                combined["evStatus"] = int(combined["evStatus"])
                combined["doorOpen"] = combined["evStatus"] == 9
                combined["doorStateText"] = (
                    "문 열림" if combined["doorOpen"] else "문 닫힘"
                )
                if combined["doorOpen"]:
                    combined["arrivalSignal"] = "evStatus=9"
                else:
                    combined.pop("arrivalSignal", None)
            except (TypeError, ValueError):
                combined.pop("doorOpen", None)
                combined.pop("doorStateText", None)
                combined.pop("arrivalSignal", None)

        # The COMMAX getEvStatus schema contains only the current floor,
        # basement flag, direction, operating state and two hall-call bits.
        # In this building the two bits were physically verified to clear
        # independently at B2 (callUp) and the home floor (callDown).  Expose
        # those as pending hall calls, never as a cabin destination.
        pending_call_floors: list[str] = []
        if "callDown" in combined or "callUp" in combined:
            try:
                if int(combined.get("callDown", 0)) == 1:
                    pending_call_floors.append(f"{self.elevator_home_floor}층")
                if int(combined.get("callUp", 0)) == 1:
                    pending_call_floors.append("B2")
            except (TypeError, ValueError):
                pending_call_floors = []
            combined["pendingCallFloors"] = pending_call_floors
            combined["pendingCallText"] = (
                "대기 호출층 · " + " · ".join(pending_call_floors)
                if pending_call_floors
                else "대기 호출층 · 없음"
            )
        combined["cabinDestinationAvailable"] = False
        combined["cabinDestinationText"] = "차내 목적층 · 홈넷 미제공"

        old = self.state["elevator"]
        if old != combined:
            self.state["elevator"] = combined
            self._publish("elevator.status", combined)

    def command_event(self, data: dict[str, Any]) -> None:
        with self.lock:
            if data.get("kind") == "elevator_call":
                call = self.state["elevatorCall"]
                if call["status"] != "server_accepted":
                    call["status"] = data["status"]
                if data["status"] == "sent":
                    call["lastSentAt"] = iso_now()
            self._publish("control.command", data)

    def elevator_call_queued(
        self,
        cooldown_seconds: int,
        requested_epoch: int | None = None,
        target: str = "우리 층",
    ) -> None:
        with self.lock:
            call = self.state["elevatorCall"]
            requested = int(time.time()) if requested_epoch is None else requested_epoch
            call["status"] = "queued"
            call["target"] = target
            call["lastRequestedAt"] = iso_now()
            call["lastRequestedEpoch"] = requested
            call["availableAtEpoch"] = requested + cooldown_seconds
            self._publish("elevator.call", dict(call))

    def restore_elevator_call(self, requested_epoch: int, cooldown_seconds: int) -> None:
        with self.lock:
            call = self.state["elevatorCall"]
            call["status"] = "sent"
            call["target"] = "우리 층"
            call["lastRequestedEpoch"] = requested_epoch
            call["lastRequestedAt"] = datetime.fromtimestamp(requested_epoch).astimezone().isoformat(timespec="seconds")
            call["availableAtEpoch"] = requested_epoch + cooldown_seconds

    def set_elevator_call_receipt(self, receipt: dict[str, Any]) -> None:
        with self.lock:
            call = self.state["elevatorCall"]
            requested = int(call["lastRequestedEpoch"])
            received = int(receipt.get("updatedEpoch", 0))
            if (
                receipt.get("accepted") is True
                and requested > 0
                and requested <= received <= requested + 10
                and call["status"] != "server_accepted"
            ):
                call["status"] = "server_accepted"
                call["lastConfirmedAt"] = receipt.get("updated") or iso_now()
                self._publish("elevator.call", dict(call))

    def _load_visitor_history(self) -> None:
        path_text = self.visitor_config.get("historyPath")
        if not path_text:
            return
        try:
            saved = json.loads(Path(path_text).read_text(encoding="utf-8"))
            history = saved.get("history", []) if isinstance(saved, dict) else []
            if isinstance(history, list):
                limit = max(1, min(200, int(self.visitor_config.get("historyLimit", 50))))
                history = [item for item in history if isinstance(item, dict)][:limit]
                for item in history:
                    item.setdefault("location", self._visitor_location(item))
                    if not item.get("deviceIp"):
                        item["deviceIp"] = self._visitor_device_ip(item)
                self.state["visitors"]["history"] = history
                changed = self._prune_visitor_history_locked()
                self.state["visitors"]["lastEvent"] = (
                    self.state["visitors"]["history"][0]
                    if self.state["visitors"]["history"] else None
                )
                if changed:
                    self._write_visitor_history_locked()
        except (OSError, ValueError, TypeError):
            pass

    def _persist_visitor_history_locked(self) -> None:
        self._prune_visitor_history_locked()
        self._write_visitor_history_locked()

    def _write_visitor_history_locked(self) -> None:
        path_text = self.visitor_config.get("historyPath")
        if not path_text:
            return
        path = Path(path_text)
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            temp_path = path.with_suffix(path.suffix + ".tmp")
            temp_path.write_text(
                json.dumps({"history": self.state["visitors"]["history"]}, ensure_ascii=False),
                encoding="utf-8",
            )
            os.replace(temp_path, path)
        except OSError:
            pass

    def _visitor_event_epoch(self, event: dict[str, Any]) -> int | None:
        for key in ("receivedEpoch", "detectedEpoch"):
            try:
                value = int(event.get(key, 0))
            except (TypeError, ValueError):
                value = 0
            if value > 0:
                return value
        for key in ("receivedAt", "detectedAt", "photoCapturedAt"):
            value = str(event.get(key, "")).strip()
            if not value:
                continue
            try:
                return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())
            except (TypeError, ValueError, OverflowError):
                continue
        return None

    def _prune_visitor_history_locked(self, now_epoch: int | None = None) -> bool:
        """Remove visitor metadata and photos older than the configured retention."""
        retention_days = max(1, int(self.visitor_config.get("retentionDays", 90)))
        cutoff = int(now_epoch if now_epoch is not None else time.time()) - retention_days * 86400
        history = self.state["visitors"].get("history", [])
        kept = [
            item for item in history
            if not isinstance(item, dict)
            or self._visitor_event_epoch(item) is None
            or int(self._visitor_event_epoch(item) or 0) >= cutoff
        ]
        changed = len(kept) != len(history)
        if changed:
            self.state["visitors"]["history"] = kept
            current = self.state["visitors"].get("lastEvent") or {}
            if current and all(item.get("id") != current.get("id") for item in kept):
                self.state["visitors"]["lastEvent"] = kept[0] if kept else None

        photo_dir_text = self.visitor_config.get("photoDir")
        if photo_dir_text:
            photo_dir = Path(photo_dir_text)
            if photo_dir.is_dir():
                for photo in photo_dir.glob("*.jpg"):
                    try:
                        if int(photo.stat().st_mtime) < cutoff:
                            photo.unlink()
                            changed = True
                    except OSError:
                        continue
        return changed

    def _visitor_location(self, event: dict[str, Any]) -> str:
        source_id = str(event.get("sourceId", ""))
        locations = self.visitor_config.get("sourceLocations", {})
        if isinstance(locations, dict) and source_id in locations:
            return str(locations[source_id])[:64]
        return "세대 현관" if event.get("kind") == "unit_entrance" else "중앙현관"

    def _visitor_device_ip(self, event: dict[str, Any]) -> str:
        source_id = str(event.get("sourceId", ""))
        device_ips = self.visitor_config.get("sourceDeviceIps", {})
        if isinstance(device_ips, dict) and source_id in device_ips:
            return str(device_ips[source_id])[:64]
        return ""

    def _visitor_source_id(self, device_ip: str) -> str:
        device_ip = str(device_ip or "").strip()
        device_ips = self.visitor_config.get("sourceDeviceIps", {})
        if isinstance(device_ips, dict):
            for source_id, configured_ip in device_ips.items():
                if device_ip and device_ip == str(configured_ip).strip():
                    return str(source_id)[:64]
        return ""

    def record_visitor_ring(self, data: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        """Record one passive visitor-ring detection and publish it to app clients."""
        with self.lock:
            now_epoch = int(time.time())
            visitors = self.state["visitors"]
            previous = visitors.get("lastEvent") or {}
            local_id = str(data.get("localCallId") or "")
            if local_id and not re.fullmatch(r"[0-9a-f]{32}", local_id):
                raise ValueError("invalid localCallId")
            if local_id:
                for old in visitors.get("history", []):
                    if old.get("localCallId") == local_id:
                        return dict(old), False
            signal = str(data.get("signal", ""))[:64]
            requested_kind = str(data.get("kind", ""))
            call_place = int(data.get("callPlace", 0) or 0)
            if requested_kind in {"central_entrance", "unit_entrance"}:
                kind = requested_kind
            elif signal in {"sip-invite-lobby", "sipua-offering-wallpad"} and call_place != 2:
                kind = "central_entrance"
            else:
                kind = "unit_entrance"
            detected_at = str(data.get("detectedAt") or iso_now())[:64]
            try:
                detected_epoch = int(datetime.fromisoformat(detected_at.replace("Z", "+00:00")).timestamp())
            except (TypeError, ValueError, OverflowError):
                detected_epoch = now_epoch
            device_ip = str(data.get("deviceIp", ""))[:64]
            source_id = str(data.get("sourceId", ""))[:64]
            if not source_id:
                source_id = self._visitor_source_id(device_ip)
            dedupe_seconds = max(5, int(self.visitor_config.get("dedupeSeconds", 45)))
            previous_epoch = int(previous.get("detectedEpoch", previous.get("receivedEpoch", 0)))
            same_source = str(previous.get("sourceId", "")) == source_id
            source_can_merge = same_source or not source_id or not previous.get("sourceId")
            if (
                previous.get("kind") == kind
                and (not local_id or not previous.get("localCallId")
                     or local_id == previous.get("localCallId"))
                and source_can_merge
                and 0 <= detected_epoch - previous_epoch < dedupe_seconds
            ):
                changed = False
                if source_id and not previous.get("sourceId"):
                    previous["sourceId"] = source_id
                    previous["location"] = self._visitor_location(previous)
                    changed = True
                if device_ip and not previous.get("deviceIp"):
                    previous["deviceIp"] = device_ip
                    changed = True
                if local_id and not previous.get("localCallId"):
                    previous["localCallId"] = local_id
                    changed = True
                if data.get("source") == "wallpad-sipua":
                    previous["wallpadDetected"] = True
                    previous["callPlace"] = call_place
                    changed = True
                if changed:
                    visitors["lastEvent"] = previous
                    self._persist_visitor_history_locked()
                    self._publish("visitor.updated", dict(previous))
                return dict(previous), False

            ring_seconds = max(10, int(self.visitor_config.get("ringSeconds", 35)))
            event = {
                "id": uuid.uuid4().hex,
                "localCallId": local_id,
                "kind": kind,
                "title": "중앙현관 호출" if kind == "central_entrance" else "세대 현관 호출",
                "source": str(data.get("source", "r6850-passive"))[:64],
                "sourceId": source_id,
                "deviceIp": str(device_ip or self._visitor_device_ip({"sourceId": source_id}))[:64],
                "signal": signal,
                "detectedAt": detected_at,
                "detectedEpoch": detected_epoch,
                "receivedAt": iso_now(),
                "receivedEpoch": now_epoch,
                "expiresAtEpoch": min(now_epoch, detected_epoch) + ring_seconds,
                "confidence": "observed_signature",
                "wallpadDetected": data.get("source") == "wallpad-sipua",
                "callPlace": call_place,
                "callState": "ringing",
            }
            event["location"] = self._visitor_location(event)
            limit = max(1, min(200, int(self.visitor_config.get("historyLimit", 50))))
            history = sorted([event, *visitors.get("history", [])],
                             key=lambda item: int(item.get("detectedEpoch", item.get("receivedEpoch", 0))),
                             reverse=True)[:limit]
            current = history[0]
            visitors.update({"ringing": int(current.get("expiresAtEpoch", 0)) > now_epoch,
                             "lastEvent": current, "history": history})
            self._persist_visitor_history_locked()
            self._publish("visitor.ring" if event["expiresAtEpoch"] > now_epoch else "visitor.updated", event)
            return dict(event), True

    def record_auto_unlock(self, data: dict[str, Any]) -> dict[str, Any]:
        if (data.get("kind") != "central_entrance" or data.get("callPlace") != 5
                or not re.fullmatch(r"[0-9a-f]{32}", str(data.get("localCallId", "")))):
            raise ValueError("verified central ring with localCallId required")
        status = data.get("autoUnlockStatus")
        if status not in {"confirmed", "failed", "skipped", "unconfirmed"}:
            raise ValueError("invalid auto-unlock result")
        with self.lock:
            event, _ = self.record_visitor_ring(data)
            visitors = self.state["visitors"]
            original = next(item for item in visitors["history"] if item["id"] == event["id"])
            # A later partial upload must not undo a confirmed door-open.
            if original.get("autoUnlockStatus") != "confirmed":
                original["autoUnlockStatus"] = status
                original["autoUnlockDetail"] = str(data.get("detail", ""))[:256]
            if status == "confirmed":
                original["unlockedAt"] = str(data.get("unlockedAt") or iso_now())[:64]
                original["callState"] = "ended"
                original["callStateUpdatedAt"] = original["unlockedAt"]
                original["expiresAtEpoch"] = min(int(original.get("expiresAtEpoch", 0)), int(time.time()))
            if (visitors.get("lastEvent") or {}).get("id") == original["id"]:
                visitors["lastEvent"] = original
                visitors["ringing"] = int(original.get("expiresAtEpoch", 0)) > int(time.time())
            self._persist_visitor_history_locked()
            self._publish("visitor.updated", dict(original))
            return dict(original)

    def update_visitor_call_state(self, event_id: str, state: str) -> dict[str, Any]:
        """Update the one live wallpad SIP call after an acknowledged UI action."""
        if state not in {"ringing", "connected", "ended"}:
            raise ValueError("invalid visitor call state")
        with self.lock:
            visitors = self.state["visitors"]
            event = visitors.get("lastEvent") or {}
            if event.get("id") != event_id:
                raise LookupError("visitor call not found")
            updated_at = iso_now()
            event["callState"] = state
            event["callStateUpdatedAt"] = updated_at
            if state == "connected":
                event.setdefault("answeredAt", updated_at)
                event["expiresAtEpoch"] = max(
                    int(event.get("expiresAtEpoch", 0)), int(time.time()) + 300
                )
                visitors["ringing"] = True
            elif state == "ended":
                event["expiresAtEpoch"] = int(time.time())
                visitors["ringing"] = False
            visitors["lastEvent"] = event
            self._persist_visitor_history_locked()
            self._publish("visitor.updated", dict(event))
            return dict(event)

    def mark_visitor_unlocked(self, event_id: str) -> dict[str, Any]:
        """Record a confirmed native wallpad door-open before ending its call."""
        with self.lock:
            visitors = self.state["visitors"]
            event = visitors.get("lastEvent") or {}
            if event.get("id") != event_id:
                raise LookupError("visitor call not found")
            event["unlockedAt"] = iso_now()
            visitors["lastEvent"] = event
            self._persist_visitor_history_locked()
            return dict(event)

    def wait_for_wallpad_call(self, event_id: str, timeout: float = 4.0) -> dict[str, Any] | None:
        """Wait for the wallpad SIP observer to merge into a passive ring event."""
        deadline = time.monotonic() + max(0.0, float(timeout))
        with self.changed:
            while True:
                visitors = self.state["visitors"]
                event = visitors.get("lastEvent") or {}
                if event.get("id") != event_id:
                    return None
                if event.get("wallpadDetected") and int(event.get("callPlace", 0) or 0) > 0:
                    return dict(event)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.changed.wait(remaining)

    def attach_visitor_photo(self, photo_url: str, metadata: dict[str, Any]) -> dict[str, Any]:
        """Attach a wallpad-captured image to the matching active visitor event."""
        with self.lock:
            now_epoch = int(time.time())
            visitors = self.state["visitors"]
            requested_kind = str(metadata.get("kind", ""))
            event = visitors.get("lastEvent") or {}
            explicit_id = str(metadata.get("eventId") or "")
            if explicit_id:
                event = next((item for item in visitors.get("history", [])
                              if item.get("id") == explicit_id), None)
                if event is None:
                    raise ValueError("visitor photo event not found")
                if requested_kind and event.get("kind") != requested_kind:
                    raise ValueError("visitor photo kind does not match event")
            event_epoch = int(event.get("receivedEpoch", 0))
            if not explicit_id and (
                not event
                or now_epoch - event_epoch > 180
                or (requested_kind and event.get("kind") != requested_kind)
            ):
                signal = "sipua-offering-wallpad"
                event, _ = self.record_visitor_ring(
                    {
                        "source": "wallpad-sipua",
                        "signal": signal,
                        "kind": requested_kind or "central_entrance",
                        "sourceId": metadata.get("sourceId", ""),
                        "deviceIp": metadata.get("deviceIp", ""),
                        "detectedAt": metadata.get("capturedAt") or iso_now(),
                    }
                )
                visitors = self.state["visitors"]
                event = visitors.get("lastEvent") or event
            event["photoUrl"] = photo_url
            event["photoCapturedAt"] = str(metadata.get("capturedAt") or iso_now())[:64]
            event["photoSource"] = "wallpad-visitor-capture"
            if (visitors.get("lastEvent") or {}).get("id") == event["id"]:
                visitors["lastEvent"] = event
            self._persist_visitor_history_locked()
            self._publish("visitor.photo", dict(event))
            return dict(event)

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            if self._prune_visitor_history_locked():
                self._write_visitor_history_locked()
                self._snapshot_cache_sequence = -1
                self._snapshot_cache_json = ""
            if self._snapshot_cache_sequence != self.sequence:
                self._snapshot_cache_json = json.dumps(self.state, ensure_ascii=False)
                self._snapshot_cache_sequence = self.sequence
            snapshot = json.loads(self._snapshot_cache_json)
            visitor = snapshot.get("visitors", {})
            last_event = visitor.get("lastEvent") or {}
            active = int(last_event.get("expiresAtEpoch", 0)) > int(time.time())
            visitor["ringing"] = active
            if not active and str(last_event.get("callState", "")) in {"ringing", "connected"}:
                last_event["callState"] = "ended"
                event_id = str(last_event.get("id", ""))
                for item in visitor.get("history", []):
                    if str(item.get("id", "")) == event_id:
                        item["callState"] = "ended"
                        break
            snapshot["sequence"] = self.sequence
            snapshot["serverTime"] = iso_now()
            return snapshot

    def wait_for_change(self, after: int, timeout: float = 20.0) -> dict[str, Any] | None:
        deadline = time.monotonic() + timeout
        with self.changed:
            while self.sequence <= after:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.changed.wait(remaining)
            for event in self.events:
                if event["sequence"] > after:
                    return json.loads(json.dumps(event, ensure_ascii=False))
            return {"sequence": self.sequence, "type": "state.refresh", "data": self.snapshot()}


@dataclass
class OutboundCommand:
    command_id: str
    kind: str
    zone: int
    frame: bytes
    description: str
    expected: dict[str, Any]
    attempts: int = 0
    last_sent: float = 0.0
    max_attempts: int = 5


class EW11Client(threading.Thread):
    def __init__(
        self,
        config: dict[str, Any],
        store: StateStore,
        frame_log_path: Path | None = None,
        raw_log_path: Path | None = None,
        raw_log_seconds: float | None = None,
    ) -> None:
        super().__init__(name="ew11-client", daemon=True)
        self.config = config
        self.store = store
        self.frame_log_path = frame_log_path
        self.raw_log_path = raw_log_path
        self.raw_log_deadline = (
            time.monotonic() + raw_log_seconds
            if raw_log_path is not None and raw_log_seconds is not None
            else None
        )
        self.commands: queue.Queue[OutboundCommand] = queue.Queue()
        self.stop_event = threading.Event()
        self.last_receive = 0.0
        self.pending: OutboundCommand | None = None

    def submit(self, command: OutboundCommand) -> None:
        self.commands.put(command)

    def run(self) -> None:
        host = self.config["host"]
        port = int(self.config["port"])
        reconnect = float(self.config.get("reconnectSeconds", 2))
        while not self.stop_event.is_set():
            try:
                with socket.create_connection((host, port), timeout=5) as sock:
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    sock.settimeout(0.1)
                    self.store.set_ew11(connected=True, error=None)
                    self._read_loop(sock)
            except OSError as exc:
                self.store.set_ew11(connected=False, error=str(exc))
                self.stop_event.wait(reconnect)

    def _read_loop(self, sock: socket.socket) -> None:
        stream = FrameStream()
        idle_seconds = float(self.config.get("busIdleMs", 300)) / 1000
        while not self.stop_event.is_set():
            try:
                data = sock.recv(8192)
                if not data:
                    raise ConnectionError("EW11 closed the TCP connection")
                self.last_receive = time.monotonic()
                self._log_raw(data)
                for frame in stream.feed(data):
                    decoded = self.store.apply_frame(frame, stream.discarded_bytes)
                    self._log_frame(decoded.as_dict(), stream.discarded_bytes)
                    self._acknowledge(decoded)
            except socket.timeout:
                pass
            self._send_when_idle(sock, idle_seconds)

    def _log_frame(self, decoded: dict[str, Any], discarded_bytes: int) -> None:
        if self.frame_log_path is None:
            return
        record = {
            "at": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "epoch": time.time(),
            "discardedBytes": discarded_bytes,
            **decoded,
        }
        try:
            self.frame_log_path.parent.mkdir(parents=True, exist_ok=True)
            with self.frame_log_path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
                handle.write("\n")
        except OSError as exc:
            self.store.set_ew11(connected=True, error=f"frame log: {exc}")

    def _log_raw(self, data: bytes) -> None:
        if self.raw_log_path is None:
            return
        if self.raw_log_deadline is not None and time.monotonic() >= self.raw_log_deadline:
            self.raw_log_path = None
            return
        record = {
            "at": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "epoch": time.time(),
            "length": len(data),
            "hex": data.hex().upper(),
        }
        try:
            self.raw_log_path.parent.mkdir(parents=True, exist_ok=True)
            with self.raw_log_path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
                handle.write("\n")
        except OSError as exc:
            self.store.set_ew11(connected=True, error=f"raw log: {exc}")

    def _send_when_idle(self, sock: socket.socket, idle_seconds: float) -> None:
        now = time.monotonic()
        if self.pending is not None and self.pending.attempts >= self.pending.max_attempts:
            if now - self.pending.last_sent >= 1.0:
                self.store.command_event(
                    {
                        "id": self.pending.command_id,
                        "kind": self.pending.kind,
                        "zone": self.pending.zone,
                        "description": self.pending.description,
                        "frame": hex_frame(self.pending.frame),
                        "status": "failed",
                        "attempts": self.pending.attempts,
                    }
                )
                self.pending = None
            return

        if self.pending is not None and self.pending.attempts > 0 and now - self.pending.last_sent < 0.8:
            return
        if now - self.last_receive < idle_seconds:
            return

        if self.pending is None:
            try:
                self.pending = self.commands.get_nowait()
            except queue.Empty:
                return

        command = self.pending
        sock.sendall(command.frame)
        command.attempts += 1
        command.last_sent = now
        self.last_receive = time.monotonic()
        self.store.command_event(
            {
                "id": command.command_id,
                "kind": command.kind,
                "zone": command.zone,
                "description": command.description,
                "frame": hex_frame(command.frame),
                "status": "sent",
                "attempt": command.attempts,
            }
        )
        if command.kind == "elevator_call":
            # This bus frame has no trustworthy ACK: never retry automatically.
            self.pending = None

    def _acknowledge(self, decoded) -> None:
        command = self.pending
        if command is None:
            return
        values = decoded.values
        if command.kind == "light_power":
            if decoded.kind not in ("light_ack", "light_status"):
                return
            if values.get("channel") != command.zone:
                return
        elif command.kind == "batch_switch_power":
            if decoded.kind != "batch_switch_status":
                return
        else:
            if decoded.kind not in ("thermostat_ack", "thermostat_status"):
                return
            if values.get("zone") != command.zone:
                return
        if any(values.get(key) != value for key, value in command.expected.items()):
            return
        self.store.command_event(
            {
                "id": command.command_id,
                "kind": command.kind,
                "zone": command.zone,
                "description": command.description,
                "frame": hex_frame(command.frame),
                "status": "acknowledged",
                "attempts": command.attempts,
                "response": decoded.raw,
            }
        )
        self.pending = None


class ElevatorPoller(threading.Thread):
    def __init__(self, config: dict[str, Any], store: StateStore) -> None:
        super().__init__(name="elevator-poller", daemon=True)
        self.config = config
        self.store = store
        self.fresh_seconds = max(5, int(config.get("freshSeconds", 12)))
        self.retain_seconds = max(
            self.fresh_seconds, int(config.get("retainSeconds", 120))
        )
        self.last_good: dict[str, Any] = {}
        self.consecutive_failures = 0
        self.stop_event = threading.Event()

    def normalize_source(
        self, source: dict[str, Any], now_epoch: int | None = None
    ) -> dict[str, Any]:
        now_epoch = int(time.time()) if now_epoch is None else now_epoch
        normalized = dict(source)
        floor = normalized.get("floor", normalized.get("carFloor"))
        if floor is not None:
            try:
                floor = int(floor)
                basement = bool(int(normalized.get("isBasement", 0)))
                normalized["floor"] = floor
                normalized["isBasement"] = basement
                normalized["displayFloor"] = elevator_floor_label(floor, basement)
            except (TypeError, ValueError):
                pass
        updated_epoch = int(normalized.get("updatedEpoch", 0))
        age = max(0, now_epoch - updated_epoch) if updated_epoch else None
        normalized["available"] = bool(normalized.get("available"))
        normalized["fresh"] = bool(
            normalized["available"]
            and age is not None
            and age <= self.fresh_seconds
        )
        normalized["ageSeconds"] = age
        normalized["connectionState"] = (
            "live" if normalized["fresh"] else "stale"
        )
        normalized["reconnecting"] = False
        normalized["consecutiveFailures"] = 0
        normalized.pop("error", None)
        self.consecutive_failures = 0
        if normalized["available"] and updated_epoch:
            self.last_good = dict(normalized)
        return normalized

    def retained_source(
        self, error: Exception | str, now_epoch: int | None = None
    ) -> dict[str, Any]:
        now_epoch = int(time.time()) if now_epoch is None else now_epoch
        self.consecutive_failures += 1
        if not self.last_good:
            return {
                "available": False,
                "fresh": False,
                "reconnecting": True,
                "connectionState": "disconnected",
                "consecutiveFailures": self.consecutive_failures,
                "error": str(error),
                "updatedAt": iso_now(),
            }

        retained = dict(self.last_good)
        updated_epoch = int(retained.get("updatedEpoch", 0))
        age = max(0, now_epoch - updated_epoch) if updated_epoch else None
        retained["ageSeconds"] = age
        retained["fresh"] = age is not None and age <= self.fresh_seconds
        retained["available"] = age is not None and age <= self.retain_seconds
        retained["reconnecting"] = True
        retained["connectionState"] = (
            "reconnecting" if retained["available"] else "disconnected"
        )
        retained["consecutiveFailures"] = self.consecutive_failures
        retained["error"] = str(error)
        retained["lastFetchErrorAt"] = iso_now()
        return retained

    def run(self) -> None:
        url = self.config.get("statusUrl")
        if not url:
            return
        interval = float(self.config.get("pollSeconds", 1))
        while not self.stop_event.is_set():
            try:
                with urllib.request.urlopen(url, timeout=1.5) as response:
                    source = json.load(response)
                if not source.get("available"):
                    self.store.set_elevator(
                        self.retained_source("elevator source unavailable")
                    )
                else:
                    self.store.set_elevator(self.normalize_source(source))
            except Exception as exc:
                self.store.set_elevator(self.retained_source(exc))
            self.stop_event.wait(interval)


class ElevatorCallReceiptPoller(threading.Thread):
    def __init__(self, config: dict[str, Any], store: StateStore) -> None:
        super().__init__(name="elevator-call-receipt-poller", daemon=True)
        self.config = config
        self.store = store
        self.stop_event = threading.Event()

    def run(self) -> None:
        url = self.config.get("callReceiptUrl")
        if not url:
            return
        interval = float(self.config.get("pollSeconds", 1))
        while not self.stop_event.is_set():
            try:
                with urllib.request.urlopen(url, timeout=1.5) as response:
                    receipt = json.load(response)
                self.store.set_elevator_call_receipt(receipt)
            except Exception:
                pass
            self.stop_event.wait(interval)


class OfficialStatePoller(threading.Thread):
    """Keep device state current without depending on the EXT2 RS-485 tap."""

    def __init__(
        self,
        config: dict[str, Any],
        client: OfficialCommaxClient,
        store: StateStore,
    ) -> None:
        super().__init__(name="official-state-poller", daemon=True)
        self.config = config
        self.client = client
        self.store = store
        self.stop_event = threading.Event()

    def run(self) -> None:
        if not self.config.get("enabled", False) or not self.config.get(
            "statePollingEnabled", False
        ):
            return
        interval = max(3.0, min(60.0, float(self.config.get("pollSeconds", 5))))
        while not self.stop_event.is_set():
            try:
                self.store.set_official_devices(self.client.refresh_devices())
            except Exception as exc:
                self.store.set_official_state_error(str(exc))
            self.stop_event.wait(interval)


class AptiParkingPoller(threading.Thread):
    """Poll Apartmenti activity and emit only newly observed vehicle movements."""

    def __init__(
        self,
        config: dict[str, Any],
        client: AptiParkingClient,
        store: StateStore,
    ) -> None:
        super().__init__(name="apti-parking-poller", daemon=True)
        self.config = config
        self.client = client
        self.store = store
        self.stop_event = threading.Event()
        default_state = Path(
            config.get("sessionPath", ROOT / "state" / "apti-parking-session.json")
        ).with_name("apti-parking-events.json")
        self.state_path = Path(config.get("activityStatePath", default_state))
        self.seen: list[str] = []
        self.initialized = False
        self._load()

    def _load(self) -> None:
        if not self.state_path.is_file():
            return
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8"))
            self.seen = [str(item) for item in value.get("seen", []) if item]
            self.initialized = bool(value.get("initialized", False))
        except (OSError, ValueError, TypeError):
            self.seen = []
            self.initialized = False

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        temp.write_text(
            json.dumps(
                {"initialized": self.initialized, "seen": self.seen[-500:]},
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        try:
            os.chmod(temp, 0o600)
        except OSError:
            pass
        os.replace(temp, self.state_path)
        try:
            os.chmod(self.state_path, 0o600)
        except OSError:
            pass

    def run_once(self) -> None:
        if not self.client.summary()["connected"]:
            self.store.set_parking_activity([], "아파트아이 로그인이 필요합니다")
            return
        result = self.client.recent_activity(limit=100, months=2)
        activity = result["activity"]
        self.store.set_parking_activity(activity)
        notification_entries = [
            item for item in activity if item.get("vehicleType") != "registered"
        ]
        current_ids = [
            str(item.get("notificationId") or item.get("id") or "")
            for item in notification_entries
            if item.get("notificationId") or item.get("id")
        ]
        if not self.initialized:
            self.seen = current_ids[-500:]
            self.initialized = True
            self._save()
            return
        known = set(self.seen)
        new_entries = [
            item for item in reversed(notification_entries)
            if str(item.get("notificationId") or item.get("id") or "") not in known
        ]
        for entry in new_entries:
            emitted = dict(entry)
            emitted["detectedAt"] = iso_now()
            self.store.publish_parking_vehicle(emitted)
            event_id = str(entry.get("notificationId") or entry.get("id") or "")
            if event_id:
                self.seen.append(event_id)
                known.add(event_id)
        self.seen = list(dict.fromkeys(self.seen))[-500:]
        self._save()

    def run(self) -> None:
        if not self.config.get("enabled", False):
            return
        interval = max(10.0, min(300.0, float(self.config.get("pollSeconds", 20))))
        while not self.stop_event.is_set():
            try:
                self.run_once()
            except Exception as exc:
                self.store.set_parking_activity([], str(exc))
            self.stop_event.wait(interval)


class CommaxParkingPoller(threading.Thread):
    """Emit household vehicle entry/exit changes from the COMMAX official service."""

    def __init__(
        self,
        config: dict[str, Any],
        client: OfficialCommaxClient,
        store: StateStore,
    ) -> None:
        super().__init__(name="commax-parking-poller", daemon=True)
        self.config = config
        self.client = client
        self.store = store
        self.stop_event = threading.Event()
        session_path = Path(
            config.get("sessionPath", ROOT / "state" / "commax-official-session.json")
        )
        self.state_path = Path(
            config.get("parkingActivityStatePath", session_path.with_name("commax-parking-events.json"))
        )
        self.seen: list[str] = []
        self.initialized = False
        self._load()

    def _load(self) -> None:
        if not self.state_path.is_file():
            return
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8"))
            self.seen = [str(item) for item in value.get("seen", []) if item]
            self.initialized = bool(value.get("initialized", False))
        except (OSError, ValueError, TypeError):
            self.seen = []
            self.initialized = False

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        temp.write_text(
            json.dumps(
                {"initialized": self.initialized, "seen": self.seen[-500:]},
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        try:
            os.chmod(temp, 0o600)
        except OSError:
            pass
        os.replace(temp, self.state_path)
        try:
            os.chmod(self.state_path, 0o600)
        except OSError:
            pass

    def run_once(self) -> None:
        if not self.client.summary().get("connected"):
            return
        result = self.client.parking_activity(limit=50)
        activity = list(result.get("activity", []))
        current_ids = [
            str(item.get("notificationId") or item.get("id") or "")
            for item in activity
            if item.get("notificationId") or item.get("id")
        ]
        if not self.initialized:
            self.seen = current_ids[-500:]
            self.initialized = True
            self._save()
            return
        known = set(self.seen)
        new_entries = [
            item for item in reversed(activity)
            if str(item.get("notificationId") or item.get("id") or "") not in known
        ]
        for entry in new_entries:
            emitted = dict(entry)
            emitted["vehicleType"] = "registered"
            emitted["vehicleTypeLabel"] = "세대차량"
            emitted["detectedAt"] = iso_now()
            self.store.publish_parking_vehicle(emitted)
            event_id = str(entry.get("notificationId") or entry.get("id") or "")
            if event_id:
                self.seen.append(event_id)
                known.add(event_id)
        self.seen = list(dict.fromkeys(self.seen))[-500:]
        self._save()

    def run(self) -> None:
        if not self.config.get("enabled", False):
            return
        interval = max(10.0, min(300.0, float(self.config.get("parkingPollSeconds", 20))))
        while not self.stop_event.is_set():
            try:
                self.run_once()
            except Exception:
                pass
            self.stop_event.wait(interval)


class WallpadParkingHistory:
    """Persist real wallpad parking signals and pair entry/exit into one row."""

    def __init__(self, config: dict[str, Any], store: StateStore) -> None:
        self.config = config
        self.store = store
        self.lock = threading.RLock()
        self.state_path = Path(
            config.get("statePath", ROOT / "state" / "wallpad-parking-events.json")
        )
        self.apti_diagnostic_path = Path(
            config.get(
                "aptiNotificationDiagnosticsPath",
                self.state_path.with_name("apti-notification-diagnostics.json"),
            )
        )
        self.limit = max(20, min(500, int(config.get("historyLimit", 100))))
        self.rows: list[dict[str, Any]] = []
        self.last_event_at: str | None = None
        self.last_probe_at: str | None = None
        self._load()

    @staticmethod
    def _plate_key(value: Any) -> str:
        return re.sub(r"[\s-]+", "", str(value or "")).upper()

    @staticmethod
    def _event_epoch(value: str) -> float:
        text = str(value or "").strip()
        if not text:
            return 0.0
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            # COMMAX portal timestamps have no offset, but they are apartment
            # local time.  The server runs in UTC, so treating a naive portal
            # timestamp as server-local created a second row nine hours away
            # from the same wallpad event.
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone(timedelta(hours=9)))
            return parsed.timestamp()
        except ValueError:
            return 0.0

    def _load(self) -> None:
        if not self.state_path.is_file():
            return
        try:
            saved = json.loads(self.state_path.read_text(encoding="utf-8"))
            rows = saved.get("rows", [])
            if isinstance(rows, list):
                self.rows = [item for item in rows if isinstance(item, dict)][: self.limit]
            self.last_event_at = saved.get("lastEventAt")
            self.last_probe_at = saved.get("lastProbeAt")
        except (OSError, ValueError, TypeError):
            self.rows = []

    def _save_locked(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(
                {
                    "rows": self.rows[: self.limit],
                    "lastEventAt": self.last_event_at,
                    "lastProbeAt": self.last_probe_at,
                },
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        try:
            os.chmod(temporary, 0o600)
        except OSError:
            pass
        os.replace(temporary, self.state_path)

    def record_apartmenti_diagnostic(
        self, payload: dict[str, Any], status: str, error: str = ""
    ) -> None:
        """Keep a small private trail so the next real gate push is diagnosable."""
        entry = {
            "receivedAt": iso_now(),
            "occurredAt": str(payload.get("occurredAt") or "")[:64],
            "title": str(payload.get("title") or "")[:256],
            "text": str(payload.get("text") or "")[:1500],
            "movement": str(payload.get("movement") or "")[:32],
            "channelId": str(payload.get("channelId") or "")[:128],
            "status": status[:64],
            "error": error[:500],
        }
        with self.lock:
            saved: list[dict[str, Any]] = []
            if self.apti_diagnostic_path.is_file():
                try:
                    candidate = json.loads(
                        self.apti_diagnostic_path.read_text(encoding="utf-8")
                    )
                    if isinstance(candidate, list):
                        saved = [item for item in candidate if isinstance(item, dict)]
                except (OSError, ValueError, TypeError):
                    saved = []
            saved.append(entry)
            self.apti_diagnostic_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.apti_diagnostic_path.with_suffix(
                self.apti_diagnostic_path.suffix + ".tmp"
            )
            temporary.write_text(
                json.dumps(saved[-20:], ensure_ascii=False), encoding="utf-8"
            )
            try:
                os.chmod(temporary, 0o600)
            except OSError:
                pass
            os.replace(temporary, self.apti_diagnostic_path)

    def record(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = iso_now()
        if payload.get("probe") is True:
            with self.lock:
                self.last_probe_at = now
                self._save_locked()
            return {"accepted": True, "probe": True, "receivedAt": now}

        event_type = int(payload.get("type") or 0)
        movement = str(payload.get("movement") or ("입차" if event_type == 1 else "출차" if event_type == 2 else ""))
        if movement not in {"입차", "출차"}:
            raise ValueError("parking event type must be 1(entry) or 2(exit)")
        car_number = str(payload.get("carNumber") or "").strip()
        plate_key = self._plate_key(car_number)
        if not plate_key:
            raise ValueError("carNumber is required")
        occurred_at = str(payload.get("occurredAt") or now).strip()
        event_epoch = self._event_epoch(occurred_at) or time.time()
        aliases = self.config.get("vehicleAliases", {})
        display_number = str(aliases.get(plate_key) or car_number)
        source = str(payload.get("source") or "wallpad").strip()
        if source not in {"wallpad", "apartmenti_notification"}:
            source = "wallpad"
        id_prefix = "apti-push-" if source == "apartmenti_notification" else "wallpad-"
        source_label = "아파트아이 알림" if source == "apartmenti_notification" else "월패드"

        with self.lock:
            # Android broadcasts can be repeated. Treat an identical event for
            # the same plate within 15 seconds as one physical gate event.
            for row in self.rows:
                if self._plate_key(row.get("carNumber")) != plate_key:
                    continue
                candidate = row.get("entryAt") if movement == "입차" else row.get("exitAt")
                if candidate and abs(self._event_epoch(str(candidate)) - event_epoch) <= 15:
                    return {"accepted": True, "duplicate": True, "event": dict(row)}

            if movement == "출차":
                target = next(
                    (
                        row
                        for row in self.rows
                        if self._plate_key(row.get("carNumber")) == plate_key
                        and row.get("entryAt")
                        and not row.get("exitAt")
                    ),
                    None,
                )
                if target is None:
                    target = {
                        "id": id_prefix + uuid.uuid4().hex,
                        "notificationId": id_prefix + uuid.uuid4().hex,
                        "carNumber": car_number,
                        "displayCarNumber": display_number,
                        "entryAt": "",
                        "source": source,
                        "sourceLabel": source_label,
                        "vehicleType": "registered",
                        "vehicleTypeLabel": "세대차량",
                    }
                    self.rows.append(target)
                target["exitAt"] = occurred_at
                # Entry and exit are separate phone notifications even though
                # the history UI keeps them on one row.
                target["notificationId"] = id_prefix + uuid.uuid4().hex
                target["status"] = "출차"
                target["movement"] = "출차"
                target["occurredAt"] = occurred_at
            else:
                target = {
                    "id": id_prefix + uuid.uuid4().hex,
                    "notificationId": id_prefix + uuid.uuid4().hex,
                    "carNumber": car_number,
                    "displayCarNumber": display_number,
                    "entryAt": occurred_at,
                    "exitAt": "",
                    "status": "입차",
                    "movement": "입차",
                    "occurredAt": occurred_at,
                    "source": source,
                    "sourceLabel": source_label,
                    "vehicleType": "registered",
                    "vehicleTypeLabel": "세대차량",
                }
                self.rows.append(target)

            target["lastEventSource"] = source
            signal_dummy = str(payload.get("dummy") or "").strip()[:256]
            lookup_samples = payload.get("lookupSamples")
            if signal_dummy:
                target["wallpadSignalDummy"] = signal_dummy
            if isinstance(lookup_samples, list):
                target["wallpadLookupSamples"] = [
                    str(item or "").strip()[:64] for item in lookup_samples[:8]
                ]

            self.rows.sort(
                key=lambda row: max(
                    self._event_epoch(str(row.get("entryAt") or "")),
                    self._event_epoch(str(row.get("exitAt") or "")),
                    self._event_epoch(str(row.get("occurredAt") or "")),
                ),
                reverse=True,
            )
            self.rows = self.rows[: self.limit]
            self.last_event_at = now
            self.last_probe_at = now
            self._save_locked()
            emitted = dict(target)

        # Older Android clients deduplicate parking pushes by `id`.  Keep the
        # stable row id in persisted history, but make the SSE event id follow
        # notificationId so an exit on the same row still produces a push.
        emitted["rowId"] = emitted.get("id")
        emitted["id"] = emitted.get("notificationId") or emitted.get("id")

        self.store.publish_parking_vehicle(emitted)
        return {"accepted": True, "duplicate": False, "event": emitted}

    def record_apartmenti_notification(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Convert an Apartmenti household-car movement push to history."""
        title = str(payload.get("title") or "").strip()[:256]
        text = str(payload.get("text") or "").strip()[:1024]
        combined = re.sub(r"\s+", " ", f"{title} {text}").strip()
        compact = re.sub(r"[\s-]+", "", combined).upper()

        aliases = self.config.get("vehicleAliases", {})
        normalized_aliases = {
            self._plate_key(key): key for key in aliases if self._plate_key(key)
        }
        resident_markers = (
            "입주민차량", "입주민 차량", "세대차량", "세대 차량", "등록차량", "등록 차량",
        )
        has_resident_marker = any(marker in combined for marker in resident_markers)
        has_visitor_marker = "방문차량" in combined or "방문 차량" in combined
        contains_configured_plate = any(
            normalized and normalized in compact for normalized in normalized_aliases
        )
        if has_visitor_marker and not has_resident_marker:
            raise ValueError("resident vehicle notification is required")
        if not has_resident_marker and not contains_configured_plate:
            raise ValueError("resident vehicle notification is required")

        explicit_movement = str(payload.get("movement") or "").strip()
        if explicit_movement in {"입차", "출차"}:
            movement = explicit_movement
        elif any(word in compact for word in ("출차", "출고", "출문", "진출", "나감", "나갔")):
            movement = "출차"
        elif any(word in compact for word in ("입차", "입고", "입문", "진입", "들어옴", "들어왔")):
            movement = "입차"
        else:
            raise ValueError("entry or exit text is required")

        plate_key = ""
        car_number = ""
        full_plate = re.search(r"\d{2,3}[가-힣]\d{4}", compact)
        if full_plate:
            car_number = full_plate.group(0)
            plate_key = self._plate_key(car_number)
        else:
            for normalized, original in normalized_aliases.items():
                if normalized in compact:
                    plate_key = normalized
                    car_number = original
                    break
        if not plate_key:
            raise ValueError("registered vehicle number was not found")

        matching_alias = next(
            (original for normalized, original in normalized_aliases.items() if plate_key.endswith(normalized)),
            "",
        )
        if normalized_aliases and not matching_alias:
            raise ValueError("notification is not for a configured household vehicle")
        if matching_alias:
            car_number = matching_alias

        return self.record({
            "movement": movement,
            "carNumber": car_number,
            "occurredAt": str(payload.get("occurredAt") or iso_now()),
            "source": "apartmenti_notification",
        })

    def snapshot(self, limit: int = 7) -> dict[str, Any]:
        with self.lock:
            return {
                "activity": [dict(item) for item in self.rows[: max(1, limit)]],
                "lastEventAt": self.last_event_at,
                "lastProbeAt": self.last_probe_at,
                "available": bool(self.last_probe_at),
            }


class AppServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        store: StateStore,
        client: EW11Client,
        official_client: OfficialCommaxClient | dict[str, Any],
        config: dict[str, Any] | None = None,
    ):
        # Retain the pre-official-integration constructor for local tools and
        # tests while production passes an explicit authenticated client.
        if config is None:
            config = official_client
            official_client = OfficialCommaxClient(config.get("officialCommax", {}))
        super().__init__(address, AppHandler)
        self.store = store
        self.ew11_client = client
        self.official_client = official_client
        self.config = config
        self.apti_parking_client = AptiParkingClient(config.get("aptiParking", {}))
        self.wallpad_parking = WallpadParkingHistory(
            config.get("wallpadBridge", {}), store
        )
        visitor_config = config.get("visitors", {})
        self.call_settings = WallpadCallSettings(
            Path(config.get("wallpadBridge", {}).get("settingsStatePath")
                 or Path(visitor_config.get("historyPath", ROOT / "state" / "visitors.json")).parent
                 / "wallpad-call-settings.json"), visitor_config.get("sourceLocations", {}))
        self.visitor_photo_dir = Path(
            visitor_config.get("photoDir", ROOT / "state" / "visitor-photos")
        )
        self.visitor_photo_dir.mkdir(parents=True, exist_ok=True)
        self.elevator_call_lock = threading.Lock()
        self.door_unlock_lock = threading.Lock()
        door_config = config.get("doorUnlock", {})
        self.door_unlock_state_path = Path(
            door_config.get("statePath", ROOT / "state" / "door-unlock-attempts.json")
        )
        self.door_unlock_attempts: dict[str, dict[str, Any]] = {}
        self.wallpad_command_changed = threading.Condition(threading.RLock())
        # A visitor can answer, start audio, unlock, and hang up in quick
        # succession. Keep those commands in call order instead of allowing a
        # later command to replace an earlier one for the same event.
        self.wallpad_commands: dict[str, deque[dict[str, Any]]] = {}
        self.wallpad_command_results: dict[str, dict[str, Any]] = {}
        self.unit_door_requests: dict[str, dict[str, Any]] = {}
        self.unit_door_bridge_seen = 0.0
        self.unit_door_last_request = 0.0
        self.visitor_audio_lock = threading.RLock()
        self.visitor_audio_sessions: dict[str, VisitorAudioSession] = {}
        if self.door_unlock_state_path.is_file():
            try:
                saved = json.loads(self.door_unlock_state_path.read_text(encoding="utf-8"))
                attempts = saved.get("attempts", {})
                if isinstance(attempts, dict):
                    self.door_unlock_attempts = attempts
            except (OSError, ValueError, TypeError):
                pass
        self.cctv_lock = threading.BoundedSemaphore(
            max(1, int(config.get("cctv", {}).get("maxConcurrent", 1)))
        )
        self.unit_door = UnitDoorRelay(
            float(config.get("cctv", {}).get("unitDoorRequestSeconds", 12))
        )
        self.last_elevator_call = 0.0
        elevator_config = config.get("elevator", {})
        self.call_state_path = Path(elevator_config.get("callStatePath", ROOT / "state" / "elevator-call.json"))
        if elevator_config.get("callEnabled") and self.call_state_path.is_file():
            try:
                previous = json.loads(self.call_state_path.read_text(encoding="utf-8"))
                requested_epoch = int(previous["requestedEpoch"])
                cooldown = max(30, int(elevator_config.get("callCooldownSeconds", 120)))
                elapsed = max(0, time.time() - requested_epoch)
                if elapsed < cooldown:
                    self.last_elevator_call = time.monotonic() - elapsed
                    store.restore_elevator_call(requested_epoch, cooldown)
            except (OSError, ValueError, KeyError, TypeError):
                pass

    def reserve_door_unlock(self, event: dict[str, Any], request_id: str) -> bool:
        event_id = str(event["id"])
        with self.door_unlock_lock:
            if event_id in self.door_unlock_attempts:
                return False
            self.door_unlock_attempts[event_id] = {
                "requestId": request_id,
                "kind": event.get("kind"),
                "location": event.get("location"),
                "state": "dispatching",
                "createdAt": iso_now(),
            }
            self._persist_door_unlock_attempts_locked()
            return True

    def finish_door_unlock(self, event_id: str, state: str, detail: str = "") -> None:
        with self.door_unlock_lock:
            attempt = self.door_unlock_attempts.get(event_id)
            if attempt is None:
                return
            attempt["state"] = state
            attempt["finishedAt"] = iso_now()
            if detail:
                attempt["detail"] = detail[:256]
            self._persist_door_unlock_attempts_locked()

    def _persist_door_unlock_attempts_locked(self) -> None:
        self.door_unlock_state_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = self.door_unlock_state_path.with_suffix(self.door_unlock_state_path.suffix + ".tmp")
        temp_path.write_text(
            json.dumps({"attempts": self.door_unlock_attempts}, ensure_ascii=False),
            encoding="utf-8",
        )
        os.replace(temp_path, self.door_unlock_state_path)

    def send_door_unlock(
        self, route: dict[str, Any], event: dict[str, Any], request_id: str
    ) -> dict[str, Any]:
        if route.get("transport") == "wallpad_queue":
            return self.send_wallpad_door_unlock(route, event, request_id)
        if route.get("transport") != "http":
            raise ValueError("unsupported door unlock transport")
        endpoint = str(route.get("endpointUrl", ""))
        parsed = urlparse(endpoint)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("invalid door unlock endpoint")
        payload = json.dumps(
            {
                "action": "unlock",
                "requestId": request_id,
                "event": {
                    "id": event.get("id"),
                    "kind": event.get("kind"),
                    "location": event.get("location"),
                    "sourceId": event.get("sourceId"),
                    "deviceIp": event.get("deviceIp"),
                },
            },
            ensure_ascii=False,
        ).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        bearer = str(route.get("bearerToken", ""))
        if bearer:
            headers["Authorization"] = "Bearer " + bearer
        request = urllib.request.Request(endpoint, data=payload, headers=headers, method="POST")
        timeout = max(1.0, min(10.0, float(route.get("timeoutSeconds", 3))))
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if not 200 <= response.status < 300:
                raise OSError(f"door unlock endpoint returned {response.status}")
            raw = response.read(65536)
        result = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(result, dict) or result.get("accepted") is not True:
            raise ValueError("door unlock endpoint did not confirm acceptance")
        return result

    def send_wallpad_door_unlock(
        self, route: dict[str, Any], event: dict[str, Any], request_id: str
    ) -> dict[str, Any]:
        return self.send_wallpad_call_command(route, event, request_id, "unlock")

    def send_wallpad_call_command(
        self,
        route: dict[str, Any],
        event: dict[str, Any],
        request_id: str,
        action: str,
    ) -> dict[str, Any]:
        if action not in {"answer", "hangup", "unlock", "audio_start"}:
            raise ValueError("unsupported wallpad call command")
        timeout = max(3.0, min(20.0, float(route.get("timeoutSeconds", 12))))
        command = {
            "id": uuid.uuid4().hex,
            "action": action,
            "requestId": request_id,
            "eventId": str(event.get("id", "")),
            "kind": str(event.get("kind", "")),
            "sourceId": str(event.get("sourceId", "")),
            "deviceIp": str(event.get("deviceIp", "")),
            "createdAtEpoch": int(time.time()),
            "expiresAtEpoch": int(time.time() + timeout),
            "delivered": False,
        }
        with self.wallpad_command_changed:
            pending = self.wallpad_commands.setdefault(command["eventId"], deque())
            pending.append(command)
            self.wallpad_command_changed.notify_all()
            deadline = time.monotonic() + timeout
            while command["id"] not in self.wallpad_command_results:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._remove_wallpad_command_locked(command["eventId"], command["id"])
                    self.wallpad_command_changed.notify_all()
                    raise TimeoutError(f"wallpad {action} confirmation timed out")
                self.wallpad_command_changed.wait(remaining)
            result = self.wallpad_command_results.pop(command["id"])
        if result.get("ok") is not True:
            raise PermissionError(str(result.get("detail") or f"wallpad rejected {action}"))
        return {
            "accepted": True,
            "action": action,
            "source": "wallpad_sip_view",
            "confirmed": True,
            "detail": str(result.get("detail", ""))[:256],
        }

    def request_unit_door(self, request_id: str) -> dict[str, Any]:
        if not re.fullmatch(r"[0-9a-f]{32}", request_id):
            raise ValueError("invalid requestId")
        with self.wallpad_command_changed:
            self.unit_door_requests = {
                key: value for key, value in self.unit_door_requests.items()
                if time.time() - value["createdAtEpoch"] < 300
            }
            if request_id in self.unit_door_requests:
                return self.unit_door_result(request_id)
            if self.unit_door_bridge_seen <= 0 or time.monotonic() - self.unit_door_bridge_seen > 30:
                raise ConnectionError("월패드 연결을 확인해 주세요")
            if self.store.snapshot()["visitors"].get("ringing"):
                raise PermissionError("방문 호출 중에는 해당 호출의 문 열기를 사용해 주세요")
            if self.unit_door_last_request > 0 and time.monotonic() - self.unit_door_last_request < 15:
                raise PermissionError("문열기 요청 처리 중입니다. 잠시 후 다시 눌러 주세요")
            self.unit_door_last_request = time.monotonic()
            self.unit_door_requests[request_id] = {
                "id": request_id, "action": "unit_monitor_unlock", "kind": "unit_entrance",
                "createdAtEpoch": int(time.time()), "expiresAtEpoch": int(time.time()) + 15,
                "createdAtEpochMs": int(time.time() * 1000),
                "delivered": False, "state": "pending", "detail": "현관 연결 중",
            }
            result = self.unit_door_result(request_id)
        # Notify after releasing the command lock; the camera wait takes locks
        # in the reverse order to atomically check its state and wait.
        self.wake_unit_door_waiter()
        return result

    def unit_door_result(self, request_id: str) -> dict[str, Any]:
        with self.wallpad_command_changed:
            value = self.unit_door_requests.get(request_id)
            if value is None:
                raise ValueError("문열기 요청을 찾을 수 없습니다")
            if value["state"] == "pending" and time.time() >= value["expiresAtEpoch"]:
                value.update(state="unknown" if value["delivered"] else "expired",
                             detail="결과를 확인하지 못했습니다. 자동으로 다시 보내지 않습니다")
            return {"requestId": request_id, "state": value["state"], "detail": value["detail"],
                    "physicalOpenConfirmed": False,
                    "queueMs": value.get("queueMs"), "prepareMs": value.get("prepareMs"),
                    "signalMs": value.get("signalMs")}

    def take_unit_door_command(self) -> dict[str, Any] | None:
        with self.wallpad_command_changed:
            self.unit_door_bridge_seen = time.monotonic()
            for key, value in self.unit_door_requests.items():
                state = self.unit_door_result(key)["state"]
                if state == "pending" and not value["delivered"]:
                    value["delivered"] = True
                    value["queueMs"] = max(0, int(time.time() * 1000) - value["createdAtEpochMs"])
                    return {name: value[name] for name in (
                        "id", "action", "kind", "createdAtEpoch", "expiresAtEpoch")}
            return None

    def wake_unit_door_waiter(self) -> None:
        with self.unit_door.changed:
            self.unit_door.changed.notify_all()

    def wait_unit_door_command(self, timeout: float, settings_revision: int) -> dict[str, Any] | None:
        deadline = time.monotonic() + max(0.0, min(10.0, timeout))
        with self.unit_door.changed:
            while True:
                command = self.take_unit_door_command()
                if command is not None or self.unit_door.desired()["active"]:
                    return command
                if self.call_settings.desired()["revision"] != settings_revision:
                    return None
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.unit_door.changed.wait(remaining)

    def visitor_route(self, event: dict[str, Any]) -> dict[str, Any]:
        kind = str(event.get("kind", ""))
        if kind == "central_entrance":
            route_name = "central"
        elif kind == "unit_entrance":
            route_name = "unit"
        else:
            raise ValueError("unsupported visitor call type")
        return self.config.get("doorUnlock", {}).get(route_name, {})

    def _remove_wallpad_command_locked(self, event_id: str, command_id: str) -> None:
        pending = self.wallpad_commands.get(event_id)
        if pending is None:
            return
        retained = deque(item for item in pending if item.get("id") != command_id)
        if retained:
            self.wallpad_commands[event_id] = retained
        else:
            self.wallpad_commands.pop(event_id, None)

    def visitor_audio_session(
        self, event_id: str, *, create: bool = False
    ) -> VisitorAudioSession | None:
        if not re.fullmatch(r"[0-9a-f]{32}", str(event_id or "")):
            raise ValueError("valid eventId is required")
        with self.visitor_audio_lock:
            now = time.time()
            expired = [
                key for key, value in self.visitor_audio_sessions.items()
                if (not value.active and now - value.last_activity > 30)
                or now - value.last_activity > 360
            ]
            for key in expired:
                self.visitor_audio_sessions.pop(key, None)
            value = self.visitor_audio_sessions.get(event_id)
            if value is None and create:
                value = VisitorAudioSession(event_id)
                self.visitor_audio_sessions[event_id] = value
            return value

    def close_visitor_audio(self, event_id: str) -> None:
        value = self.visitor_audio_session(event_id)
        if value is not None:
            value.close()

    def next_wallpad_command(self, event_id: str, timeout: float = 20.0) -> dict[str, Any] | None:
        deadline = time.monotonic() + timeout
        with self.wallpad_command_changed:
            while True:
                pending = self.wallpad_commands.get(event_id)
                while pending and int(pending[0].get("expiresAtEpoch", 0)) <= int(time.time()):
                    pending.popleft()
                if pending is not None and not pending:
                    self.wallpad_commands.pop(event_id, None)
                    pending = None
                if pending:
                    command = pending[0]
                    if not command.get("delivered"):
                        command["delivered"] = True
                        return {key: value for key, value in command.items() if key != "delivered"}
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.wallpad_command_changed.wait(remaining)

    def finish_wallpad_command(self, result: dict[str, Any]) -> None:
        command_id = str(result.get("commandId", ""))
        if not re.fullmatch(r"[0-9a-f]{32}", command_id):
            raise ValueError("invalid commandId")
        with self.wallpad_command_changed:
            standalone = self.unit_door_requests.get(command_id)
            if standalone is not None:
                if not standalone["delivered"]:
                    raise ValueError("command was not delivered")
                # Late ACKs remain unknown; they must never trigger another write.
                if self.unit_door_result(command_id)["state"] == "pending":
                    standalone.update(state="sent" if result.get("ok") is True else "failed",
                                      detail=str(result.get("detail", ""))[:256])
                    for key in ("prepareMs", "signalMs"):
                        duration = result.get(key)
                        if isinstance(duration, int) and 0 <= duration <= 30000:
                            standalone[key] = duration
                return
            self.wallpad_command_results[command_id] = {
                "ok": result.get("ok") is True,
                "detail": str(result.get("detail", ""))[:256],
            }
            for event_id in list(self.wallpad_commands):
                pending = self.wallpad_commands[event_id]
                if any(item.get("id") == command_id for item in pending):
                    self._remove_wallpad_command_locked(event_id, command_id)
                    break
            self.wallpad_command_changed.notify_all()


class AppHandler(BaseHTTPRequestHandler):
    server: AppServer

    def log_message(self, fmt: str, *args: Any) -> None:
        if self.path.startswith((
            "/api/status",
            "/api/events",
            "/api/wallpad/camera/desired",
            "/api/wallpad/camera/frame",
        )):
            return
        print(f"{self.client_address[0]} - {fmt % args}")

    def _authorized(self) -> bool:
        token = str(self.server.config.get("http", {}).get("apiToken", ""))
        if not token:
            return True
        bearer = self.headers.get("Authorization", "").removeprefix("Bearer ")
        return secrets.compare_digest(token, bearer)

    def _ingest_authorized(self) -> bool:
        token = str(self.server.config.get("visitors", {}).get("ingestToken", ""))
        supplied = self.headers.get("X-Homepad-Ingest", "")
        return bool(token) and secrets.compare_digest(token, supplied)

    def _json(self, status: int, value: Any) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 65536:
            raise ValueError("invalid request body")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("JSON object required")
        return value

    def _read_bytes(self, maximum: int) -> bytes:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > maximum:
            raise ValueError("invalid request body")
        value = self.rfile.read(length)
        if len(value) != length:
            raise ValueError("incomplete request body")
        return value

    @staticmethod
    def _merge_household_parking(
        wallpad_rows: list[dict[str, Any]],
        portal_rows: list[dict[str, Any]],
        limit: int,
    ) -> list[dict[str, Any]]:
        merged: list[dict[str, Any]] = []

        def plate_key(row: dict[str, Any]) -> str:
            return re.sub(r"[\s-]+", "", str(row.get("carNumber") or "")).upper()

        def row_epoch(row: dict[str, Any]) -> float:
            value = str(row.get("entryAt") or row.get("occurredAt") or "")
            return WallpadParkingHistory._event_epoch(value)

        for row in list(wallpad_rows) + list(portal_rows):
            if not isinstance(row, dict):
                continue
            key = plate_key(row)
            epoch = row_epoch(row)
            duplicate = False
            for existing in merged:
                if plate_key(existing) != key or not key:
                    continue
                existing_epoch = row_epoch(existing)
                if epoch and existing_epoch and abs(epoch - existing_epoch) <= 300:
                    # The wallpad event arrives first and is used for the push;
                    # the portal can later add the exit time.  Keep one row and
                    # enrich it with whichever source has the more complete
                    # information.
                    for field in ("entryAt", "exitAt", "displayCarNumber"):
                        if not existing.get(field) and row.get(field):
                            existing[field] = row[field]
                    if row.get("exitAt") and not existing.get("exitAt"):
                        existing["exitAt"] = row["exitAt"]
                    if existing.get("exitAt"):
                        existing["status"] = "출차"
                        existing["movement"] = "출차"
                    duplicate = True
                    break
            if not duplicate:
                merged.append(dict(row))

        merged.sort(
            key=lambda row: max(
                WallpadParkingHistory._event_epoch(str(row.get("entryAt") or "")),
                WallpadParkingHistory._event_epoch(str(row.get("exitAt") or "")),
                WallpadParkingHistory._event_epoch(str(row.get("occurredAt") or "")),
            ),
            reverse=True,
        )
        return merged[: max(1, limit)]

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/api/wallpad/camera/desired":
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            query = parse_qs(urlparse(self.path).query)
            command = None
            if query.get("unitDoorControl") == ["1"]:
                try:
                    wait_seconds = float(query.get("waitSeconds", ["0"])[0])
                    revision = int(query.get("settingsRevision", ["-1"])[0])
                except ValueError:
                    self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid wait parameters"})
                    return
                command = self.server.wait_unit_door_command(wait_seconds, revision)
            value = self.server.unit_door.desired()
            value.update({"channel": 100, "captureIntervalMs": 500,
                          "callSettings": self.server.call_settings.desired()})
            if query.get("unitDoorControl") == ["1"]:
                value["unitDoorCommand"] = command
                value["commandWait"] = True
            self._json(HTTPStatus.OK, value)
            return
        if path == "/api/wallpad/commands/next":
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            query = parse_qs(urlparse(self.path).query)
            event_id = str(query.get("eventId", [""])[0])
            if not re.fullmatch(r"[0-9a-f]{32}", event_id):
                self._json(HTTPStatus.BAD_REQUEST, {"error": "valid eventId is required"})
                return
            command = self.server.next_wallpad_command(event_id)
            self._json(HTTPStatus.OK, {"command": command})
            return
        if path == "/api/wallpad/audio/phone":
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            self._visitor_audio_read("phone")
            return
        if path.startswith("/api/") and not self._authorized():
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        if path == "/api/calls/settings":
            self._json(HTTPStatus.OK, self.server.call_settings.snapshot())
            return
        if path.startswith("/api/unit-door/requests/"):
            try:
                self._json(HTTPStatus.OK, self.server.unit_door_result(path.rsplit("/", 1)[1]))
            except ValueError as exc:
                self._json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
            return
        if path == "/api/status":
            value = self.server.store.snapshot()
            official = self.server.official_client.summary()
            value["integration"]["officialSession"] = {
                "available": official["enabled"],
                "authenticated": official["connected"],
                "username": official["username"],
                "gatewayNo": official["gatewayNo"],
                "deviceCount": official["deviceCount"],
            }
            value["integration"]["officialElevator"] = official["elevator"]
            value["integration"]["callSettings"] = self.server.call_settings.snapshot()
            parking = self.server.apti_parking_client.summary()
            value["integration"]["aptiParking"] = {
                "available": parking["enabled"],
                "authenticated": parking["connected"],
                "apiVerified": parking["apiVerified"],
                "apiError": parking["apiError"],
                "apiCheckedAt": parking["apiCheckedAt"],
                "apiSuccessAt": parking["apiSuccessAt"],
                "username": parking["username"],
                "loginMethod": parking["loginMethod"],
                "connectedAt": parking["connectedAt"],
            }
            value["capabilities"]["visitorParking"] = bool(
                parking["enabled"] and parking["connected"]
            )
            self._json(HTTPStatus.OK, value)
            return
        if path == "/api/official/status":
            self._json(HTTPStatus.OK, self.server.official_client.summary(include_devices=True))
            return
        if path == "/api/events":
            self._events()
            return
        if path == "/api/visitors":
            self._json(HTTPStatus.OK, self.server.store.snapshot()["visitors"])
            return
        parts = [part for part in path.split("/") if part]
        if len(parts) == 5 and parts[:2] == ["api", "calls"] and parts[3:] == ["audio", "visitor"]:
            self._visitor_audio_read("visitor", parts[2])
            return
        if path == "/api/parking/status":
            self._json(HTTPStatus.OK, self.server.apti_parking_client.summary())
            return
        if path == "/api/parking/history":
            try:
                query = parse_qs(urlparse(self.path).query)
                month = str(query.get("month", [datetime.now().strftime("%Y%m")])[0])
                self._json(HTTPStatus.OK, self.server.apti_parking_client.history(month))
            except PermissionError as exc:
                self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
            except (OSError, ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
            return
        if path == "/api/parking/activity":
            try:
                query = parse_qs(urlparse(self.path).query)
                months = int(str(query.get("months", ["6"])[0]))
                visitor_reservations: list[dict[str, Any]] = []
                visitor_activity: list[dict[str, Any]] = []
                household_activity: list[dict[str, Any]] = []
                parking_policy: dict[str, Any] = {
                    "totalMinutes": 6000,
                    "remainingMinutes": 6000,
                    "usedMinutes": 0,
                    "source": "default",
                }
                source_status: dict[str, dict[str, Any]] = {}

                try:
                    apti = self.server.apti_parking_client.dashboard(months=months)
                    visitor_reservations = list(apti.get("visitorReservations", []))[:3]
                    visitor_activity = list(apti.get("visitorActivity", []))[:3]
                    parking_policy = dict(apti.get("parkingPolicy") or parking_policy)
                    cached = bool(apti.get("cached", False))
                    source_status["apartmenti"] = {
                        "available": not cached,
                        "stale": cached,
                        "cachedAt": int(apti.get("cachedAt") or 0),
                        "reservationCount": len(visitor_reservations),
                        "count": len(visitor_activity),
                        "months": list(apti.get("months", [])),
                    }
                    if cached:
                        source_status["apartmenti"]["error"] = str(
                            apti.get("cacheError") or "아파트아이 연결 확인 필요"
                        )
                except (PermissionError, OSError, ValueError, TypeError) as exc:
                    source_status["apartmenti"] = {
                        "available": False,
                        "count": 0,
                        "error": str(exc),
                    }

                wallpad_snapshot = self.server.wallpad_parking.snapshot(limit=7)
                source_status["wallpad"] = {
                    "available": bool(wallpad_snapshot.get("available")),
                    "count": len(wallpad_snapshot.get("activity", [])),
                    "lastEventAt": wallpad_snapshot.get("lastEventAt"),
                    "lastProbeAt": wallpad_snapshot.get("lastProbeAt"),
                }

                try:
                    commax = self.server.official_client.parking_activity(limit=7)
                    portal_activity = list(commax.get("activity", []))
                    wallpad_activity = list(wallpad_snapshot.get("activity", []))
                    household_activity = self._merge_household_parking(
                        wallpad_activity, portal_activity, 7
                    )
                    source_status["commax"] = {
                        "available": True,
                        "count": len(household_activity),
                        "appPackageRequired": False,
                    }
                except (PermissionError, OSError, ValueError, TypeError) as exc:
                    household_activity = list(wallpad_snapshot.get("activity", []))[:7]
                    source_status["commax"] = {
                        "available": False,
                        "count": 0,
                        "appPackageRequired": False,
                        "error": str(exc),
                    }

                sources = [
                    name for name, state in source_status.items() if state.get("available")
                ]
                self._json(
                    HTTPStatus.OK,
                    {
                        "visitorActivity": visitor_activity,
                        "visitorReservations": visitor_reservations,
                        "householdActivity": household_activity,
                        "parkingPolicy": parking_policy,
                        "activity": visitor_activity + household_activity,
                        "visitorLimit": 3,
                        "householdLimit": 7,
                        "visitorQueryMonths": months,
                        "sources": sources,
                        "sourceStatus": source_status,
                        "appPackageRequired": False,
                    },
                )
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if path == "/api/cctv/channels":
            self._json(HTTPStatus.OK, self.server.store.snapshot()["cctv"])
            return
        parts = [part for part in path.split("/") if part]
        if len(parts) == 4 and parts[:3] == ["api", "visitors", "photos"]:
            self._visitor_photo(parts[3])
            return
        if len(parts) == 5 and parts[:3] == ["api", "cctv", "channels"] and parts[4] == "snapshot":
            self._cctv_snapshot(parts[3])
            return
        if len(parts) == 5 and parts[:3] == ["api", "cctv", "channels"] and parts[4] == "stream":
            self._cctv_stream(parts[3])
            return
        self._static(path)

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        parts = [part for part in path.split("/") if part]
        if parts == ["api", "cctv", "motion"]:
            token = str(self.server.config.get("http", {}).get("cctvMotionIngestToken", ""))
            supplied = self.headers.get("X-Cctv-Motion", "")
            if not token or not secrets.compare_digest(token, supplied):
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                accepted = self.server.store.record_cctv_motion(self._read_json())
                self._json(HTTPStatus.OK, {"accepted": accepted})
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if parts == ["api", "wallpad", "call-settings", "report"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                self.server.call_settings.acknowledge(self._read_json())
                self._json(HTTPStatus.OK, {"accepted": True})
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if parts == ["api", "wallpad", "auto-unlock", "events"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                body = self._read_json()
                if body.get("sourceId") not in WallpadCallSettings.SOURCES:
                    raise ValueError("unknown central entrance")
                event = self.server.store.record_auto_unlock(body)
                self._json(HTTPStatus.OK, {"accepted": True, "eventId": event["id"]})
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if parts == ["api", "wallpad", "camera", "frame"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                maximum = max(
                    65536,
                    int(self.server.config.get("cctv", {}).get("maxFrameBytes", 2097152)),
                )
                sequence = self.server.unit_door.publish(self._read_bytes(maximum), maximum)
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            self._json(HTTPStatus.ACCEPTED, {"accepted": True, "sequence": sequence})
            return
        if parts == ["api", "visitors", "ring"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            self._visitor_ring()
            return
        if parts == ["api", "visitors", "photo"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            self._visitor_photo_upload()
            return
        if parts == ["api", "wallpad", "commands", "result"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                self.server.finish_wallpad_command(self._read_json())
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            self._json(HTTPStatus.ACCEPTED, {"accepted": True})
            return
        if parts == ["api", "wallpad", "audio", "visitor"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            self._visitor_audio_push("visitor")
            return
        if parts == ["api", "wallpad", "audio", "stop"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                body = self._read_json()
                self.server.close_visitor_audio(str(body.get("eventId") or ""))
                self._json(HTTPStatus.OK, {"accepted": True})
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if parts == ["api", "parking", "wallpad-event"]:
            if not self._ingest_authorized():
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
                return
            try:
                self._json(HTTPStatus.OK, self.server.wallpad_parking.record(self._read_json()))
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            except OSError as exc:
                self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
            return
        if not self._authorized():
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        if parts == ["api", "unit-door", "unlock"]:
            if not self.server.config.get("http", {}).get("apiToken"):
                self._json(HTTPStatus.UNAUTHORIZED, {"error": "서버 인증 설정이 필요합니다"})
                return
            try:
                body = self._read_json()
                self._json(HTTPStatus.ACCEPTED, self.server.request_unit_door(str(body.get("requestId", ""))))
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            except (PermissionError, ConnectionError) as exc:
                self._json(HTTPStatus.CONFLICT, {"error": str(exc)})
            return
        if parts == ["api", "calls", "settings"]:
            try:
                updated = self.server.call_settings.update(
                    self._read_json(), on_changed=self.server.wake_unit_door_waiter)
                self._json(HTTPStatus.OK, updated)
            except (ValueError, TypeError) as exc:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            except OSError:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "월패드 설정을 저장하지 못했습니다"})
            return
        if parts == ["api", "parking", "apti-notification"]:
            payload = self._read_json()
            try:
                result = self.server.wallpad_parking.record_apartmenti_notification(
                    payload
                )
                self.server.wallpad_parking.record_apartmenti_diagnostic(
                    payload, "accepted"
                )
                self._json(HTTPStatus.OK, result)
            except (ValueError, TypeError) as exc:
                self.server.wallpad_parking.record_apartmenti_diagnostic(
                    payload, "rejected", str(exc)
                )
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            except OSError as exc:
                self.server.wallpad_parking.record_apartmenti_diagnostic(
                    payload, "error", str(exc)
                )
                self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
            return
        if parts == ["api", "official", "enroll"]:
            self._official_enroll()
            return
        if parts == ["api", "official", "refresh-inventory"]:
            self._official_refresh_inventory()
            return
        if parts == ["api", "official", "uc", "prepare"]:
            self._official_prepare_uc()
            return
        if parts == ["api", "parking", "enroll"]:
            self._apti_parking_enroll()
            return
        if parts == ["api", "parking", "reservations"]:
            self._apti_parking_reserve()
            return
        if len(parts) == 5 and parts[:3] == ["api", "parking", "reservations"] and parts[4] == "cancel":
            self._apti_parking_cancel(parts[3])
            return
        if parts == ["api", "elevator", "call"]:
            self._elevator_call()
            return
        if parts == ["api", "elevator", "b2-call"]:
            self._elevator_b2_call()
            return
        if parts == ["api", "batch-switch", "power"]:
            self._batch_switch_power()
            return
        if len(parts) == 4 and parts[:2] == ["api", "lights"] and parts[3] == "power":
            self._light_power(parts[2])
            return
        if len(parts) == 4 and parts[:2] == ["api", "calls"] and parts[3] == "unlock":
            self._door_unlock(parts[2])
            return
        if len(parts) == 4 and parts[:2] == ["api", "calls"] and parts[3] in {"answer", "hangup"}:
            self._visitor_call_control(parts[2], parts[3])
            return
        if len(parts) == 5 and parts[:2] == ["api", "calls"] and parts[3] == "audio":
            if parts[4] == "start":
                self._visitor_audio_start(parts[2])
                return
            if parts[4] == "stop":
                self._visitor_audio_stop(parts[2])
                return
            if parts[4] == "phone":
                self._visitor_audio_push("phone", parts[2])
                return
        if len(parts) != 5 or parts[:3] != ["api", "heating", "zones"]:
            self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        try:
            zone = int(parts[3])
            action = parts[4]
            body = self._read_json()
            allowed = self.server.store.allowed_controls(zone)
            if action == "setpoint":
                if "setpoint" not in allowed:
                    raise PermissionError("setpoint control is not enabled for this zone")
                temperature = int(body["temperature"])
                official_config = self.server.config.get("officialCommax", {})
                if official_config.get("controlEnabled", False):
                    nickname = official_config.get("heatingNames", {}).get(str(zone))
                    if not nickname:
                        raise LookupError(f"no official heating mapping for zone {zone}")
                    result = self.server.official_client.set_heating_setpoint(nickname, temperature)
                    result["confirmation"] = "passive_status_pending"
                    self._json(HTTPStatus.OK, result)
                    return
                frame = build_setpoint_command(zone, temperature)
                description = f"구역 {zone} 설정온도 {temperature} C"
                expected = {"setpoint": temperature}
            elif action == "power":
                enabled = bool(body["enabled"])
                permission = "power_on" if enabled else "power_off"
                if permission not in allowed:
                    raise PermissionError(f"{permission} is not enabled for this zone")
                official_config = self.server.config.get("officialCommax", {})
                if official_config.get("controlEnabled", False):
                    nickname = official_config.get("heatingNames", {}).get(str(zone))
                    if not nickname:
                        raise LookupError(f"no official heating mapping for zone {zone}")
                    result = self.server.official_client.set_heating_power(nickname, enabled)
                    result["confirmation"] = "passive_status_pending"
                    self._json(HTTPStatus.OK, result)
                    return
                frame = build_power_command(zone, enabled)
                description = f"구역 {zone} 난방 {'켜기' if enabled else '끄기'}"
                expected = {"enabled": enabled}
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "unknown action"})
                return
            command = OutboundCommand(uuid.uuid4().hex, action, zone, frame, description, expected)
            self.server.ew11_client.submit(command)
            self._json(
                HTTPStatus.ACCEPTED,
                {"accepted": True, "commandId": command.command_id, "frame": hex_frame(frame)},
            )
        except PermissionError as exc:
            self._json(HTTPStatus.CONFLICT, {"error": str(exc)})
        except (LookupError, OSError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
        except (KeyError, TypeError, ValueError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _light_power(self, channel_text: str) -> None:
        try:
            channel = int(channel_text)
            body = self._read_json()
            if not isinstance(body.get("enabled"), bool):
                raise ValueError("enabled must be boolean")
            enabled = body["enabled"]
            permission = "power_on" if enabled else "power_off"
            if permission not in self.server.store.allowed_light_controls(channel):
                raise PermissionError(f"{permission} is not enabled for light channel {channel}")
            official_config = self.server.config.get("officialCommax", {})
            if official_config.get("controlEnabled", False):
                nickname = official_config.get("lightNames", {}).get(str(channel))
                if not nickname:
                    raise LookupError(f"no official light mapping for channel {channel}")
                result = self.server.official_client.set_light(nickname, enabled)
                result["confirmation"] = "passive_status_pending"
                self._json(HTTPStatus.OK, result)
                return
            if not self.server.store.snapshot()["ew11"]["connected"]:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "EW11 is disconnected"})
                return
            frame = build_light_power_command(channel, enabled)
            command = OutboundCommand(
                uuid.uuid4().hex,
                "light_power",
                channel,
                frame,
                f"전등 {channel} {'켜기' if enabled else '끄기'}",
                {"enabled": enabled},
                max_attempts=max(1, int(self.server.config.get("lights", {}).get("maxAttempts", 1))),
            )
            self.server.ew11_client.submit(command)
            self._json(
                HTTPStatus.ACCEPTED,
                {"accepted": True, "commandId": command.command_id, "frame": hex_frame(frame)},
            )
        except PermissionError as exc:
            self._json(HTTPStatus.CONFLICT, {"error": str(exc)})
        except (LookupError, OSError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
        except (KeyError, TypeError, ValueError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _official_enroll(self) -> None:
        try:
            body = self._read_json()
            username = str(body.get("username", ""))
            password = str(body.get("password", ""))
            device_uuid = str(body.get("deviceUuid", ""))
            result = self.server.official_client.enroll(username, password, device_uuid)
            try:
                # The password is still only used by the OAuth request above.
                # UC credentials are derived from the authenticated residence
                # record, then the server identity is registered once.
                result["uc"] = self.server.official_client.prepare_uc()
            except (OSError, ValueError, LookupError, PermissionError) as exc:
                # Lighting/heating enrollment remains usable when the optional
                # UC endpoint is temporarily unavailable.
                result["ucSetupError"] = str(exc)
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _official_refresh_inventory(self) -> None:
        try:
            self._json(HTTPStatus.OK, self.server.official_client.refresh_inventory())
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _official_prepare_uc(self) -> None:
        """Register/discover the official UC identity; never opens a door."""
        try:
            result = self.server.official_client.prepare_uc()
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, LookupError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _apti_parking_enroll(self) -> None:
        try:
            body = self._read_json()
            result = self.server.apti_parking_client.enroll(
                str(body.get("account", "")),
                str(body.get("password", "")),
                str(body.get("method", "id")),
            )
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _apti_parking_reserve(self) -> None:
        try:
            body = self._read_json()
            result = self.server.apti_parking_client.reserve(
                str(body.get("carNumber", "")),
                str(body.get("visitDate", "")),
                str(self.server.config.get("aptiParking", {}).get("visitorPhone", "")),
            )
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _apti_parking_cancel(self, reservation_id: str) -> None:
        try:
            result = self.server.apti_parking_client.cancel(reservation_id)
            self._json(HTTPStatus.OK, result)
        except PermissionError as exc:
            self._json(HTTPStatus.UNAUTHORIZED, {"error": str(exc)})
        except (OSError, ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})

    def _batch_switch_power(self) -> None:
        """Control the dedicated whole-home all-off switch captured from the official app."""
        try:
            body = self._read_json()
            if not isinstance(body.get("enabled"), bool):
                raise ValueError("enabled must be boolean")
            if not self.server.config.get("batchSwitch", {}).get("enabled", False):
                raise PermissionError("batch switch control is not enabled")
            enabled = body["enabled"]
            official_config = self.server.config.get("officialCommax", {})
            if official_config.get("controlEnabled", False):
                nickname = str(official_config.get("batchNickname", "")).strip()
                if not nickname:
                    raise LookupError("no official batch switch mapping")
                result = self.server.official_client.set_batch_switch(nickname, enabled)
                result["confirmation"] = "passive_status_pending"
                self._json(HTTPStatus.OK, result)
                return
            if not self.server.store.snapshot()["ew11"]["connected"]:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "EW11 is disconnected"})
                return
            frame = build_batch_switch_command(enabled)
            command = OutboundCommand(
                uuid.uuid4().hex,
                "batch_switch_power",
                1,
                frame,
                f"일괄소등 {'ON' if enabled else 'OFF'}",
                {"enabled": enabled},
                max_attempts=max(1, int(self.server.config.get("batchSwitch", {}).get("maxAttempts", 3))),
            )
            self.server.ew11_client.submit(command)
            self._json(
                HTTPStatus.ACCEPTED,
                {
                    "accepted": True,
                    "enabled": enabled,
                    "commandId": command.command_id,
                    "frame": hex_frame(frame),
                },
            )
        except PermissionError as exc:
            self._json(HTTPStatus.CONFLICT, {"error": str(exc)})
        except (LookupError, OSError) as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
        except (KeyError, TypeError, ValueError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _cctv_snapshot(self, channel_text: str) -> None:
        config = self.server.config.get("cctv", {})
        if not config.get("enabled", False):
            self._json(HTTPStatus.CONFLICT, {"error": "CCTV relay is not enabled"})
            return
        try:
            channel = int(channel_text)
            channels = {
                int(item["id"]): item
                for item in config.get("channels", [])
                if isinstance(item, dict) and "id" in item
            }
            if channel not in channels:
                raise ValueError("unknown CCTV channel")
        except (TypeError, ValueError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        item = channels[channel]
        if item.get("source") == "wallpad_unit_door":
            self._unit_door_snapshot(channel)
            return
        if not self.server.cctv_lock.acquire(blocking=False):
            self._json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "another CCTV frame is being received"})
            return
        try:
            host = str(config.get("host", "127.0.0.1"))
            port = int(config.get("streamPort", 19731))
            timeout = float(config.get("timeoutSeconds", 8))
            max_bytes = max(65536, int(config.get("maxFrameBytes", 2097152)))
            request = b"\xfe\xfe\x08\x00\x00\x00\x00\x00\x00\x00" + bytes([channel]) + b"\x00\x00\x00"
            with socket.create_connection((host, port), timeout=timeout) as connection:
                connection.settimeout(timeout)
                connection.sendall(request)
                frame = receive_jpeg_frame(connection, max_bytes)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Homepad-Source", "homenet-mjpeg-fallback")
            self.send_header("X-Homepad-Channel", str(channel))
            self.send_header("Content-Length", str(len(frame)))
            self.end_headers()
            self.wfile.write(frame)
        except (ConnectionError, OSError, TimeoutError, ValueError) as exc:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": f"CCTV frame unavailable: {exc}"})
        finally:
            self.server.cctv_lock.release()

    def _cctv_stream(self, channel_text: str) -> None:
        config = self.server.config.get("cctv", {})
        if not config.get("enabled", False):
            self._json(HTTPStatus.CONFLICT, {"error": "CCTV relay is not enabled"})
            return
        try:
            channel = int(channel_text)
            channels = {
                int(item["id"]): item
                for item in config.get("channels", [])
                if isinstance(item, dict) and "id" in item
            }
            if channel not in channels:
                raise ValueError("unknown CCTV channel")
        except (TypeError, ValueError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        if channels[channel].get("source") == "wallpad_unit_door":
            self._unit_door_stream(channel)
            return
        if not self.server.cctv_lock.acquire(blocking=False):
            self._json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "CCTV viewer limit reached"})
            return
        started = False
        try:
            host = str(config.get("host", "127.0.0.1"))
            port = int(config.get("streamPort", 19731))
            timeout = float(config.get("timeoutSeconds", 8))
            max_bytes = max(65536, int(config.get("maxFrameBytes", 2097152)))
            request = b"\xfe\xfe\x08\x00\x00\x00\x00\x00\x00\x00" + bytes([channel]) + b"\x00\x00\x00"
            with socket.create_connection((host, port), timeout=timeout) as connection:
                connection.settimeout(timeout)
                connection.sendall(request)
                frames = iter_jpeg_frames(connection, max_bytes)
                first = next(frames)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Accel-Buffering", "no")
                self.send_header("Connection", "close")
                self.end_headers()
                started = True
                for frame in itertools.chain((first,), frames):
                    self.wfile.write(
                        b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + str(len(frame)).encode("ascii") + b"\r\n\r\n"
                    )
                    self.wfile.write(frame + b"\r\n")
                    self.wfile.flush()
        except (ConnectionError, OSError, TimeoutError, ValueError) as exc:
            if not started:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": f"CCTV stream unavailable: {exc}"})
        finally:
            self.server.cctv_lock.release()

    def _unit_door_snapshot(self, channel: int) -> None:
        relay = self.server.unit_door
        after = relay.desired()["sequence"]
        relay.request_view()
        result = relay.latest_frame(3.0)
        if result is None:
            result = relay.wait_frame(after, 11.0)
        if result is None:
            self._json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "세대 현관 카메라가 응답하지 않습니다"},
            )
            return
        sequence, frame = result
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Homepad-Source", "wallpad-unit-door")
        self.send_header("X-Homepad-Channel", str(channel))
        self.send_header("X-Homepad-Frame", str(sequence))
        self.send_header("Content-Length", str(len(frame)))
        self.end_headers()
        self.wfile.write(frame)

    def _unit_door_stream(self, channel: int) -> None:
        relay = self.server.unit_door
        after = relay.desired()["sequence"]
        started = False
        first_deadline = time.monotonic() + 20.0
        frame_deadline = first_deadline
        relay.acquire_view()
        try:
            cached = relay.latest_frame_info(300.0)
            pending = None if cached is None else (cached[0], cached[1])
            initial_frame_age_ms = None if cached is None else cached[2]
            while True:
                # acquire_view already holds demand for the whole connection.
                # A rolling snapshot lease would keep capturing for another
                # twelve seconds after the last stream viewer disconnects.
                result = pending
                pending = None
                if result is None:
                    result = relay.wait_frame(after, 1.0)
                if result is None:
                    if time.monotonic() < frame_deadline:
                        continue
                    if not started:
                        self._json(
                            HTTPStatus.SERVICE_UNAVAILABLE,
                            {"error": "세대 현관 카메라가 응답하지 않습니다"},
                        )
                    return
                after, frame = result
                frame_deadline = time.monotonic() + 15.0
                if not started:
                    self.send_response(HTTPStatus.OK)
                    self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("X-Homepad-Source", "wallpad-unit-door")
                    self.send_header("X-Homepad-Channel", str(channel))
                    if initial_frame_age_ms is not None:
                        self.send_header(
                            "X-Homepad-Initial-Frame-Age-Ms",
                            str(initial_frame_age_ms),
                        )
                    self.send_header("X-Accel-Buffering", "no")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    started = True
                self.wfile.write(
                    b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                    + str(len(frame)).encode("ascii") + b"\r\n\r\n"
                )
                self.wfile.write(frame + b"\r\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            return
        finally:
            relay.release_view()

    def _visitor_ring(self) -> None:
        config = self.server.config.get("visitors", {})
        if not config.get("enabled", False):
            self._json(HTTPStatus.CONFLICT, {"error": "visitor detection is not enabled"})
            return
        try:
            body = self._read_json()
            if body.get("signal") not in {
                "tls-client-payload-885",
                "sip-invite-lobby",
                "sipua-offering-wallpad",
            }:
                raise ValueError("unsupported visitor signal")
            event, created = self.server.store.record_visitor_ring(body)
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        self._json(
            HTTPStatus.ACCEPTED,
            {"accepted": True, "created": created, "eventId": event.get("id"),
             "callState": event.get("callState")},
        )

    def _visitor_photo_upload(self) -> None:
        try:
            body = self._read_bytes(3 * 1024 * 1024)
            if len(body) < 4 or not body.startswith(b"\xff\xd8") or not body.endswith(b"\xff\xd9"):
                raise ValueError("JPEG image required")
            kind = self.headers.get("X-Visitor-Kind", "")
            if kind not in {"", "central_entrance", "unit_entrance"}:
                raise ValueError("invalid visitor kind")
            photo_id = uuid.uuid4().hex
            path = self.server.visitor_photo_dir / f"{photo_id}.jpg"
            temp_path = path.with_suffix(".jpg.tmp")
            temp_path.write_bytes(body)
            os.replace(temp_path, path)
            metadata = {
                "kind": kind,
                "eventId": self.headers.get("X-Visitor-Event-Id", "")[:32],
                "sourceId": self.headers.get("X-Visitor-Source", "")[:64],
                "deviceIp": self.headers.get("X-Visitor-Device-Ip", "")[:64],
                "capturedAt": self.headers.get("X-Visitor-Captured-At", "")[:64],
            }
            event = self.server.store.attach_visitor_photo(
                f"/api/visitors/photos/{photo_id}.jpg", metadata
            )
            files = sorted(
                self.server.visitor_photo_dir.glob("*.jpg"),
                key=lambda item: item.stat().st_mtime,
                reverse=True,
            )
            for old in files[50:]:
                try:
                    old.unlink()
                except OSError:
                    pass
        except (ValueError, OSError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        self._json(
            HTTPStatus.ACCEPTED,
            {"accepted": True, "eventId": event.get("id"), "photoUrl": event.get("photoUrl")},
        )

    def _visitor_photo(self, file_name: str) -> None:
        if not re.fullmatch(r"[0-9a-f]{32}\.jpg", file_name):
            self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        path = self.server.visitor_photo_dir / file_name
        if not path.is_file():
            self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        try:
            body = path.read_bytes()
        except OSError as exc:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(exc)})
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Cache-Control", "private, max-age=60")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _door_unlock(self, event_id: str) -> None:
        try:
            body = self._read_json()
            if body.get("action") != "unlock":
                raise ValueError("action must be unlock")
            request_id = str(body.get("requestId", ""))
            if not re.fullmatch(r"[A-Za-z0-9._:-]{16,96}", request_id):
                raise ValueError("valid requestId is required")
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        status = self.server.store.snapshot()
        visitors = status.get("visitors", {})
        event = next(
            (item for item in visitors.get("history", []) if item.get("id") == event_id),
            None,
        )
        if event is None:
            self._json(HTTPStatus.NOT_FOUND, {"error": "visitor call not found"})
            return
        last_event = visitors.get("lastEvent") or {}
        if not visitors.get("ringing") or last_event.get("id") != event_id:
            self._json(HTTPStatus.CONFLICT, {"error": "visitor call is no longer active"})
            return
        if int(event.get("expiresAtEpoch", 0)) <= int(time.time()):
            self._json(HTTPStatus.CONFLICT, {"error": "visitor call is no longer active"})
            return
        kind = event.get("kind")
        if kind == "central_entrance":
            route_name = "central"
            capability = "centralDoorUnlock"
        elif kind == "unit_entrance":
            route_name = "unit"
            capability = "unitDoorUnlock"
        else:
            self._json(HTTPStatus.CONFLICT, {"error": "unsupported visitor call type"})
            return
        details = status.get("capabilityDetails", {}).get(capability, {})
        if not status.get("capabilities", {}).get(capability, False):
            self._json(
                HTTPStatus.CONFLICT,
                {
                    "error": details.get("reason", "문열림 장치가 아직 연결되지 않았습니다"),
                    "capability": capability,
                    "ready": False,
                    "reason": details.get("reason", "door unlock transport is not ready"),
                },
            )
            return
        route = self.server.visitor_route(event)
        if route.get("transport") == "wallpad_queue" and not event.get("wallpadDetected"):
            wallpad_event = self.server.store.wait_for_wallpad_call(event_id)
            if wallpad_event is None:
                self._json(
                    HTTPStatus.CONFLICT,
                    {"error": "월패드에 활성 호출이 확인되지 않았습니다", "retry": True},
                )
                return
            event = wallpad_event
        try:
            if not self.server.reserve_door_unlock(event, request_id):
                self._json(
                    HTTPStatus.CONFLICT,
                    {"error": "this visitor call already has an unlock attempt", "duplicate": True},
                )
                return
        except OSError as exc:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": f"could not save unlock guard: {exc}"})
            return
        try:
            result = self.server.send_door_unlock(route, event, request_id)
            self.server.finish_door_unlock(event_id, "accepted")
            if route.get("transport") == "wallpad_queue" and result.get("confirmed") is True:
                # COMMAX's native door-open action ends the active SIP call.
                # Reflect that verified transition immediately so the phone UI
                # does not remain stuck in the connected state.
                self.server.store.mark_visitor_unlocked(event_id)
                self.server.store.update_visitor_call_state(event_id, "ended")
        except Exception as exc:
            # The downstream result can be ambiguous after a timeout. Keep the
            # event reserved and never retry an access-control write automatically.
            self.server.finish_door_unlock(event_id, "unknown", str(exc))
            self._json(
                HTTPStatus.BAD_GATEWAY,
                {
                    "error": "문열림 전송 결과를 확인하지 못했습니다. 같은 호출에는 다시 보내지 않습니다",
                    "retry": False,
                },
            )
            return
        self._json(
            HTTPStatus.ACCEPTED,
            {
                "accepted": True,
                "eventId": event_id,
                "requestId": request_id,
                "transportResult": result,
            },
        )

    def _active_visitor_event(self, event_id: str) -> dict[str, Any]:
        status = self.server.store.snapshot()
        visitors = status.get("visitors", {})
        event = next(
            (item for item in visitors.get("history", []) if item.get("id") == event_id),
            None,
        )
        if event is None or (visitors.get("lastEvent") or {}).get("id") != event_id:
            raise LookupError("visitor call not found")
        if str(event.get("callState", "ringing")) not in {"ringing", "connected"}:
            raise PermissionError("visitor call is not active")
        return event

    def _visitor_audio_start(self, event_id: str) -> None:
        try:
            body = self._read_json()
            if body.get("action") != "start":
                raise ValueError("action must be start")
            request_id = str(body.get("requestId", ""))
            if not re.fullmatch(r"[A-Za-z0-9._:-]{16,96}", request_id):
                raise ValueError("valid requestId is required")
            event = self._active_visitor_event(event_id)
            if str(event.get("callState", "")) != "connected":
                raise PermissionError("visitor call must be answered first")
            status = self.server.store.snapshot()
            audio_capability = (
                "visitorUnitAudio"
                if str(event.get("kind", "")) == "unit_entrance"
                else "visitorCentralAudio"
            )
            if not status.get("capabilities", {}).get(audio_capability, False):
                raise PermissionError("visitor audio bridge is not available")
            session = self.server.visitor_audio_session(event_id, create=True)
            assert session is not None
            session.active = True
            route = self.server.visitor_route(event)
            result = self.server.send_wallpad_call_command(
                route, event, request_id, "audio_start"
            )
            self._json(
                HTTPStatus.ACCEPTED,
                {
                    "accepted": True,
                    "eventId": event_id,
                    "codec": "PCMU",
                    "sampleRate": 8000,
                    "frameMilliseconds": 20,
                    "transportResult": result,
                },
            )
        except LookupError as exc:
            self._json(HTTPStatus.NOT_FOUND, {"error": str(exc)})
        except PermissionError as exc:
            self._json(HTTPStatus.CONFLICT, {"error": str(exc)})
        except (OSError, TimeoutError) as exc:
            self.server.close_visitor_audio(event_id)
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc), "retry": True})
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _visitor_audio_stop(self, event_id: str) -> None:
        try:
            body = self._read_json()
            if body.get("action") != "stop":
                raise ValueError("action must be stop")
            self.server.close_visitor_audio(event_id)
            self._json(HTTPStatus.OK, {"accepted": True, "eventId": event_id})
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _visitor_audio_push(self, direction: str, event_id: str | None = None) -> None:
        try:
            body = self._read_json()
            requested_id = str(event_id or body.get("eventId") or "")
            session = self.server.visitor_audio_session(requested_id)
            if session is None or not session.active:
                raise LookupError("visitor audio session not found")
            frames = VisitorAudioSession.decode_frames(str(body.get("frames") or ""))
            sequence = session.push(direction, frames)
            self._json(
                HTTPStatus.ACCEPTED,
                {"accepted": True, "sequence": sequence, "frames": len(frames)},
            )
        except LookupError as exc:
            self._json(HTTPStatus.NOT_FOUND, {"error": str(exc), "active": False})
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _visitor_audio_read(self, direction: str, event_id: str | None = None) -> None:
        try:
            query = parse_qs(urlparse(self.path).query)
            requested_id = str(event_id or query.get("eventId", [""])[0])
            after = max(0, int(str(query.get("after", ["0"])[0])))
            timeout = max(0.0, min(20.0, float(str(query.get("timeout", ["10"])[0]))))
            session = self.server.visitor_audio_session(requested_id)
            if session is None:
                self._json(
                    HTTPStatus.OK,
                    {"active": False, "sequence": after, "frames": "", "count": 0},
                )
                return
            sequence, frames = session.read(direction, after, timeout)
            self._json(
                HTTPStatus.OK,
                {
                    "active": session.active,
                    "sequence": sequence,
                    "frames": VisitorAudioSession.encode_frames(frames),
                    "count": len(frames),
                    "wallpadReady": session.wallpad_ready,
                    "phoneReady": session.phone_ready,
                },
            )
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})

    def _visitor_call_control(self, event_id: str, action: str) -> None:
        try:
            body = self._read_json()
            if body.get("action") != action:
                raise ValueError(f"action must be {action}")
            request_id = str(body.get("requestId", ""))
            if not re.fullmatch(r"[A-Za-z0-9._:-]{16,96}", request_id):
                raise ValueError("valid requestId is required")
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return

        status = self.server.store.snapshot()
        visitors = status.get("visitors", {})
        event = next(
            (item for item in visitors.get("history", []) if item.get("id") == event_id),
            None,
        )
        if event is None or (visitors.get("lastEvent") or {}).get("id") != event_id:
            self._json(HTTPStatus.NOT_FOUND, {"error": "visitor call not found"})
            return
        call_state = str(event.get("callState", "ringing"))
        if action == "answer" and (not visitors.get("ringing") or call_state != "ringing"):
            self._json(HTTPStatus.CONFLICT, {"error": "visitor call is not waiting for answer"})
            return
        if action == "hangup" and call_state not in {"ringing", "connected"}:
            self._json(HTTPStatus.CONFLICT, {"error": "visitor call is not active"})
            return
        if not status.get("capabilities", {}).get("visitorCallControl", False):
            detail = status.get("capabilityDetails", {}).get("visitorCallControl", {})
            self._json(
                HTTPStatus.CONFLICT,
                {"error": detail.get("reason", "월패드 통화 제어가 준비되지 않았습니다")},
            )
            return

        if not event.get("wallpadDetected"):
            wallpad_event = self.server.store.wait_for_wallpad_call(event_id)
            if wallpad_event is None:
                self._json(
                    HTTPStatus.CONFLICT,
                    {"error": "월패드에 활성 호출이 확인되지 않았습니다", "retry": True},
                )
                return
            event = wallpad_event

        try:
            route = self.server.visitor_route(event)
            result = self.server.send_wallpad_call_command(
                route, event, request_id, action
            )
            updated = self.server.store.update_visitor_call_state(
                event_id, "connected" if action == "answer" else "ended"
            )
            if action == "hangup":
                self.server.close_visitor_audio(event_id)
        except Exception as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc), "retry": True})
            return
        self._json(
            HTTPStatus.ACCEPTED,
            {
                "accepted": True,
                "eventId": event_id,
                "requestId": request_id,
                "callState": updated.get("callState"),
                "transportResult": result,
            },
        )

    def _elevator_call(self) -> None:
        config = self.server.config.get("elevator", {})
        if not config.get("callEnabled", False):
            self._json(HTTPStatus.CONFLICT, {"error": "elevator call is not enabled"})
            return
        try:
            body = self._read_json()
            if body.get("action") != "call":
                raise ValueError("action must be call")
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        transport = str(config.get("callTransport", "ew11"))
        if transport == "ew11" and not self.server.store.snapshot()["ew11"]["connected"]:
            self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "EW11 is disconnected"})
            return
        if transport not in {"ew11", "ethernet_http"}:
            self._json(HTTPStatus.CONFLICT, {"error": "unsupported elevator call transport"})
            return

        cooldown = max(30, int(config.get("callCooldownSeconds", 120)))
        with self.server.elevator_call_lock:
            now = time.monotonic()
            remaining = cooldown - (now - self.server.last_elevator_call)
            if remaining > 0:
                self._json(HTTPStatus.CONFLICT, {"error": "call cooldown active", "retryAfterSeconds": int(remaining) + 1})
                return
            endpoint = str(config.get("callEndpointUrl", "")).strip()
            token = str(config.get("callEndpointToken", ""))
            if transport == "ethernet_http":
                if not endpoint or not token:
                    self._json(HTTPStatus.SERVICE_UNAVAILABLE, {
                        "error": "엘리베이터 연결 설정을 확인해 주세요.",
                        "code": "elevator_not_configured", "requestNotSent": True,
                    })
                    return
                # Probe only the read-only link before reserving a call. A dead
                # tunnel must not lock the user out for two minutes without a send.
                health_url = config.get("statusUrl")
                if health_url:
                    try:
                        with urllib.request.urlopen(health_url, timeout=2) as response:
                            if not isinstance(json.load(response), dict):
                                raise ValueError("invalid link response")
                    except (OSError, ValueError) as exc:
                        print(json.dumps({"event": "elevator.call.transport", "phase": "preflight",
                                          "errorType": type(exc).__name__, "requestNotSent": True}))
                        self._json(HTTPStatus.SERVICE_UNAVAILABLE, {
                            "error": "집의 엘리베이터 연결이 일시적으로 응답하지 않습니다. 호출 신호는 보내지 않았습니다. 잠시 후 다시 눌러 주세요.",
                            "code": "elevator_link_unavailable", "requestNotSent": True,
                        })
                        return
                now = time.monotonic()
            command_id = uuid.uuid4().hex
            requested_epoch = int(time.time())
            try:
                self.server.call_state_path.parent.mkdir(parents=True, exist_ok=True)
                temp_path = self.server.call_state_path.with_suffix(".tmp")
                temp_path.write_text(json.dumps({"requestedEpoch": requested_epoch}), encoding="utf-8")
                os.replace(temp_path, self.server.call_state_path)
            except OSError as exc:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": f"could not save call cooldown: {exc}"})
                return
            self.server.last_elevator_call = now
            self.server.store.elevator_call_queued(cooldown, requested_epoch, "우리 층")

            if transport == "ew11":
                command = OutboundCommand(
                    command_id,
                    "elevator_call",
                    0,
                    build_elevator_call_command(),
                    "엘리베이터 호출",
                    {},
                    max_attempts=1,
                )
                self.server.ew11_client.submit(command)
                self._json(
                    HTTPStatus.ACCEPTED,
                    {
                        "accepted": True,
                        "commandId": command.command_id,
                        "transport": "ew11",
                        "note": "queued; physical arrival not yet confirmed",
                    },
                )
                return

            request = urllib.request.Request(
                endpoint,
                data=json.dumps(
                    {"requestId": command_id, "controlToken": token}
                ).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "X-Homepad-Control": token,
                },
                method="POST",
            )
            started = time.monotonic()
            try:
                timeout = max(3.0, min(12.0, float(config.get("callTimeoutSeconds", 8))))
                recovered = False
                try:
                    with urllib.request.urlopen(request, timeout=timeout) as response:
                        result = json.loads(response.read(65536).decode("utf-8"))
                except (OSError, ValueError) as dispatch_error:
                    # Never send POST again. A matching atomically written receipt
                    # can resolve a lost HTTP reply without a second physical call.
                    receipt_url = urlparse(endpoint)._replace(
                        path="/call-command.json", query="", fragment="").geturl()
                    try:
                        with urllib.request.urlopen(receipt_url, timeout=1.5) as response:
                            result = json.load(response)
                    except (OSError, ValueError):
                        raise dispatch_error
                    if (not isinstance(result, dict)
                            or result.get("requestId") != command_id
                            or result.get("serverAccepted") is not True):
                        raise dispatch_error
                    recovered = True
                if not isinstance(result, dict) or result.get("serverAccepted") is not True:
                    raise OSError("elevator server did not confirm the call")
                if result.get("requestId") != command_id:
                    raise OSError("elevator call receipt does not match request")
                self.server.store.command_event(
                    {
                        "id": command_id,
                        "kind": "elevator_call",
                        "status": "sent",
                        "transport": "ethernet_http",
                    }
                )
                self.server.store.set_elevator_call_receipt(
                    {
                        "accepted": True,
                        "updatedEpoch": int(result.get("updatedEpoch") or time.time()),
                    }
                )
                self._json(
                    HTTPStatus.OK,
                    {
                        "accepted": True,
                        "serverAccepted": True,
                        "commandId": command_id,
                        "transport": "ethernet_http",
                        "receiptRecovered": recovered,
                        "note": "server accepted; physical arrival is shown by live elevator status",
                    },
                )
                print(json.dumps({"event": "elevator.call.transport", "commandId": command_id,
                                  "phase": "confirmed", "receiptRecovered": recovered,
                                  "elapsedMs": round((time.monotonic() - started) * 1000)}))
            except Exception as exc:
                # The server may have received an ambiguous request. Keep the
                # cooldown and never fall back to EW11 or retry automatically.
                self.server.store.command_event(
                    {
                        "id": command_id,
                        "kind": "elevator_call",
                        "status": "failed",
                        "transport": "ethernet_http",
                    }
                )
                self._json(
                    HTTPStatus.BAD_GATEWAY,
                    {
                        "accepted": False,
                        "commandId": command_id,
                        "code": "elevator_result_unknown",
                        "retry": False,
                        "error": "호출을 전달했지만 수락 결과를 확인하지 못했습니다. 중복 호출을 막기 위해 자동 재전송하지 않습니다. 앱에서 현재 층을 확인해 주세요.",
                    },
                )
                print(json.dumps({"event": "elevator.call.transport", "commandId": command_id,
                                  "phase": "unknown", "errorType": type(exc).__name__,
                                  "elapsedMs": round((time.monotonic() - started) * 1000)}))

    def _elevator_b2_call(self) -> None:
        config = self.server.config.get("elevator", {})
        if not config.get("b2CallEnabled", False):
            self._json(
                HTTPStatus.CONFLICT,
                {
                    "error": "B2 elevator call is not verified yet",
                    "required": "capture a normal B2 landing call or configure an official authenticated endpoint",
                },
            )
            return
        try:
            body = self._read_json()
            if body.get("action") != "call":
                raise ValueError("action must be call")
        except (ValueError, TypeError) as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return
        endpoint = str(config.get("b2CallEndpointUrl", "")).strip()
        token = str(config.get("b2CallEndpointToken", "")).strip()
        if not endpoint or not token:
            self._json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "B2 elevator call endpoint is not configured"},
            )
            return
        command_id = uuid.uuid4().hex
        request = urllib.request.Request(
            endpoint,
            data=json.dumps(
                {"requestId": command_id, "controlToken": token, "target": "B2"}
            ).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Homepad-Control": token},
            method="POST",
        )
        try:
            timeout = max(3.0, min(12.0, float(config.get("b2CallTimeoutSeconds", 8))))
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.loads(response.read(65536).decode("utf-8"))
            if not isinstance(result, dict) or result.get("serverAccepted") is not True:
                raise OSError("B2 elevator server did not confirm the call")
        except Exception as exc:
            self._json(
                HTTPStatus.BAD_GATEWAY,
                {"accepted": False, "commandId": command_id, "error": str(exc)},
            )
            return
        self.server.store.elevator_call_queued(
            max(30, int(config.get("b2CallCooldownSeconds", 120))),
            int(time.time()),
            "B2",
        )
        self._json(
            HTTPStatus.OK,
            {
                "accepted": True,
                "serverAccepted": True,
                "commandId": command_id,
                "target": "B2",
            },
        )

    def _events(self) -> None:
        after_text = parse_qs(urlparse(self.path).query).get("after", ["0"])[0]
        try:
            after = int(after_text)
        except ValueError:
            after = 0
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        event = self.server.store.wait_for_change(after)
        payload = {"type": "heartbeat", "sequence": after} if event is None else event
        self.wfile.write(f"data: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8"))

    def _static(self, path: str) -> None:
        if path == "/":
            path = "/index.html"
        requested = (STATIC_ROOT / unquote(path.lstrip("/"))).resolve()
        try:
            requested.relative_to(STATIC_ROOT.resolve())
        except ValueError:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if not requested.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        body = requested.read_bytes()
        content_type = mimetypes.guess_type(requested.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-cache" if requested.name == "index.html" else "public, max-age=3600")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def load_config(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="HomePad Companion local gateway")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument(
        "--frame-log",
        type=Path,
        help="append every checksum-valid EW11 frame to this JSONL file",
    )
    parser.add_argument(
        "--raw-log",
        type=Path,
        help="append every raw EW11 TCP receive chunk to this JSONL file",
    )
    parser.add_argument(
        "--raw-log-seconds",
        type=float,
        help="stop appending to --raw-log after this many seconds",
    )
    args = parser.parse_args()
    config = load_config(args.config)
    store = StateStore(config)
    official_client = OfficialCommaxClient(config.get("officialCommax", {}))
    official_state_poller = OfficialStatePoller(
        config.get("officialCommax", {}), official_client, store
    )
    client = EW11Client(
        config["ew11"],
        store,
        args.frame_log,
        args.raw_log,
        args.raw_log_seconds,
    )
    host = config["http"].get("host", "127.0.0.1")
    port = int(config["http"].get("port", 8090))
    server = AppServer((host, port), store, client, official_client, config)
    apti_parking_poller = AptiParkingPoller(
        config.get("aptiParking", {}), server.apti_parking_client, store
    )
    commax_parking_poller = CommaxParkingPoller(
        config.get("officialCommax", {}), official_client, store
    )
    client.start()
    official_state_poller.start()
    apti_parking_poller.start()
    commax_parking_poller.start()
    elevator_poller = ElevatorPoller(config.get("elevator", {}), store)
    elevator_receipt_poller = ElevatorCallReceiptPoller(
        config.get("elevator", {}), store
    )
    elevator_poller.start()
    elevator_receipt_poller.start()
    print(f"HomePad Companion: http://{host}:{port}")
    print(f"EW11: {config['ew11']['host']}:{config['ew11']['port']}")
    if args.frame_log:
        print(f"Frame log: {args.frame_log.resolve()}")
    if args.raw_log:
        print(f"Raw log: {args.raw_log.resolve()}")
    print(f"Control enabled: {bool(config.get('control', {}).get('enabled', False))}")
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        client.stop_event.set()
        official_state_poller.stop_event.set()
        apti_parking_poller.stop_event.set()
        commax_parking_poller.stop_event.set()
        elevator_poller.stop_event.set()
        elevator_receipt_poller.stop_event.set()
        elevator_poller.join(timeout=2)
        elevator_receipt_poller.join(timeout=2)
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
