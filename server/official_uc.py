"""COMMAX UC account bootstrap and door-message helpers.

This module mirrors the network-visible setup performed by the legacy
``com.commax.ipiot`` Android client.  It deliberately stops before sending a
door-open message: that message is only valid inside a live, authenticated UC
call and must be correlated with the active lobby caller.
"""

from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.error
import urllib.request
import uuid
from typing import Any


UC_DOMAIN = "commax.com"
UC_REGISTER_PATH = "/mussel/servlet/UKeyUserAction"
UC_LOOKUP_PATH = "/NSQuery"
MODERN_SERVER_LIST_URL = "https://auth-v2.commaxcloud.net/v2/server/list"
MODERN_UC_DEVICE_PATH = "/api/v1.0/users/devices"
MODERN_UC_API_KEY = "eAk5spF0Jk"
MODERN_UC_APP_ID = "com.commax.iphomeiot"
MODERN_UC_APP_VERSION = "1.0.0_76"


def _https_url(host: str, path: str) -> str:
    host = str(host or "").strip().rstrip("/")
    if not host:
        raise ValueError("COMMAX UC server address is missing")
    if not host.startswith(("http://", "https://")):
        host = "https://" + host
    return host + path


def derive_uc_profile(residence: dict[str, Any], device_uuid: str) -> dict[str, str]:
    """Build the exact identifiers used by LoginManagerService.J().

    The returned ``password`` is derived in memory and must not be persisted.
    """

    values = {
        key: str(residence.get(key) or "").strip()
        for key in ("buildingNo", "roomNo", "centerCode", "resourceNo")
    }
    device_uuid = str(device_uuid or "").strip()
    missing = [key for key, value in values.items() if not value]
    if not device_uuid:
        missing.append("deviceUuid")
    if missing:
        raise ValueError("COMMAX UC profile is missing: " + ", ".join(missing))

    building = values["buildingNo"]
    room = values["roomNo"]
    center = values["centerCode"]
    resource = values["resourceNo"]
    return {
        "id": f"{center}_{device_uuid}_{building}_{room}_IPIOT_M",
        "password": building + room,
        "firstName": building,
        "lastName": room,
        "groupCode": center,
        "groupId": f"{center}_{resource}_{building}_{room}_G",
        "uuid": device_uuid,
    }


def build_uc_registration(profile: dict[str, str], operation: str = "ADD") -> dict[str, Any]:
    operation = operation.upper()
    if operation not in {"ADD", "DELETE"}:
        raise ValueError("unsupported COMMAX UC registration operation")
    domain_id = profile["id"]
    building = profile["firstName"]
    room = profile["lastName"]
    phone = building + room
    return {
        "operationType": operation,
        "arguments": {
            "domainID": domain_id,
            "domainName": UC_DOMAIN,
            "lastName": room,
            "firstName": building,
            "displayInfo": f"{building}_{room}",
            "bizGroup": profile["groupCode"],
            "groupID": profile["groupId"],
            "cellPhone": phone,
            "localExtension": phone,
            "password": profile["password"],
            "position": "mobile",
            "email": f"{phone}@{UC_DOMAIN}",
            "networkType": "0",
            "callwaiting": "1",
            "anonymous": "0",
            "displaycaller": "1",
            "chargingType": "0",
        },
    }


def build_open_door_monitoring(
    door_password: str,
    camera_name: str,
    call_id: int,
    relay: str = "",
) -> str:
    """Build the legacy app's in-call common-entrance unlock payload."""

    door_password = str(door_password or "").strip()
    camera_name = str(camera_name or "").strip()
    if not re.fullmatch(r"\d{4}", door_password):
        raise ValueError("common-entrance door password must be four digits")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,96}", camera_name):
        raise ValueError("invalid COMMAX UC camera name")
    if not isinstance(call_id, int) or call_id < 0:
        raise ValueError("invalid COMMAX UC call id")
    parameter: dict[str, Any] = {
        "password": door_password,
        "cameraName": camera_name,
        "callId": call_id,
    }
    if relay:
        if relay not in {"1", "2"}:
            raise ValueError("relay must be 1 or 2")
        parameter["relay"] = relay
    return json.dumps(
        {"operationType": "openDoorMonitoring", "parameter": parameter},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def build_modern_uc_device_request(username: str) -> dict[str, str]:
    """Build a push-disabled UC v2 registration probe for a bridge identity.

    The bridge UUID is deterministic and distinct from the official Android
    installation, so probing the modern UC service cannot replace the phone's
    existing device record.
    """

    username = str(username or "").strip()
    if not username:
        raise ValueError("COMMAX account ID is missing")
    bridge_uuid = uuid.uuid5(
        uuid.NAMESPACE_URL, "homepad-uc-bridge:" + username
    ).hex
    return {
        "uuid": bridge_uuid,
        "huid": hashlib.sha256((username + "commax").encode()).hexdigest(),
        "deviceName": "HomePadBridge",
        "pushToken": "",
        "osType": "Android",
        "osVersion": "Server",
        "appVersion": MODERN_UC_APP_VERSION,
        "pushTokenId": MODERN_UC_APP_ID,
    }


def parse_modern_server_list(value: dict[str, Any]) -> dict[str, str]:
    """Normalize the public COMMAX Cloud v2 server-list response."""

    items = value.get("serverList") if isinstance(value, dict) else None
    if not isinstance(items, list):
        raise ValueError("COMMAX Cloud v2 server list is missing")
    result: dict[str, str] = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        server_type = str(item.get("serverType") or "").strip().lower()
        host = str(item.get("domain") or "").strip()
        port = str(item.get("port") or "").strip()
        ssl = str(item.get("ssl") or "").strip().upper() == "Y"
        if not server_type or not host:
            continue
        if server_type in {"ucrest", "ucrest-ssl"}:
            scheme = "https" if ssl else "http"
            result[server_type] = f"{scheme}://{host}" + (f":{port}" if port else "")
        elif server_type in {"sip", "stun", "turn", "outbnproxy"}:
            result[server_type] = host + (f":{port}" if port else "")
    return result


class ModernUcCompatibility:
    """Probe protocol compatibility with the current UC v2 infrastructure.

    This sends only the official device-registration request with push
    disabled.  It never starts a call and never sends a door-open message.
    """

    def __init__(self, timeout: float = 10.0) -> None:
        self.timeout = max(3.0, min(20.0, float(timeout)))

    def _json_request(
        self,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        body: dict[str, Any] | None = None,
    ) -> tuple[int, dict[str, Any]]:
        request_headers = {"Accept": "application/json"}
        request_headers.update(headers or {})
        payload = None
        method = "GET"
        if body is not None:
            request_headers["Content-Type"] = "application/json"
            payload = json.dumps(body, separators=(",", ":")).encode()
            method = "POST"
        request = urllib.request.Request(
            url, data=payload, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status = response.status
                raw = response.read(1024 * 1024)
        except urllib.error.HTTPError as exc:
            status = exc.code
            raw = exc.read(1024 * 1024)
        value = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(value, dict):
            raise ValueError("COMMAX UC v2 response was not a JSON object")
        return status, value

    def probe(self, session: dict[str, Any]) -> dict[str, Any]:
        _, server_value = self._json_request(MODERN_SERVER_LIST_URL)
        servers = parse_modern_server_list(server_value)
        rest_server = servers.get("ucrest-ssl") or servers.get("ucrest")
        if not rest_server:
            raise ValueError("COMMAX UC v2 REST server is missing")

        token = str(session.get("accessToken") or "").strip()
        username = str(session.get("username") or "").strip()
        if not token:
            raise PermissionError("COMMAX official session is not connected")
        token_type = str(session.get("tokenType") or "Bearer").strip()
        status, response = self._json_request(
            rest_server.rstrip("/") + MODERN_UC_DEVICE_PATH,
            headers={
                "Api-Key": MODERN_UC_API_KEY,
                "Authorization": f"{token_type} {token}",
                "User-Agent": f"com.commax.uc/android/{MODERN_UC_APP_VERSION}",
            },
            body=build_modern_uc_device_request(username),
        )
        header = response.get("header") if isinstance(response.get("header"), dict) else {}
        response_status = str(header.get("status") or "").strip().lower()
        accepted = 200 <= status < 300 and response_status == "success"
        code = header.get("code")
        message = str(header.get("message") or "").strip()
        return {
            "checkedAt": int(time.time()),
            "mode": "modern_uc_v2",
            "structuralCompatible": True,
            "credentialCompatible": accepted,
            "registrationAccepted": accepted,
            "httpStatus": status,
            "resultCode": code,
            "resultMessage": message,
            "servers": {
                key: servers[key]
                for key in ("ucrest-ssl", "ucrest", "sip", "stun", "turn", "outbnproxy")
                if key in servers
            },
        }


class OfficialUcBootstrap:
    """Register a distinct server-side UC identity and discover its SIP host."""

    def __init__(self, timeout: float = 10.0) -> None:
        self.timeout = max(3.0, min(20.0, float(timeout)))

    def _post(self, url: str, body: dict[str, Any]) -> dict[str, Any]:
        request = urllib.request.Request(
            url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Accept": "application/json", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read(1024 * 1024)
        except urllib.error.HTTPError as exc:
            detail = exc.read(4096).decode("utf-8", "replace")
            raise OSError(f"COMMAX UC HTTP {exc.code}: {detail[:256]}") from exc
        value = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(value, dict):
            raise ValueError("COMMAX UC response was not a JSON object")
        return value

    @staticmethod
    def _result_code(value: dict[str, Any]) -> str:
        for key in ("result_code", "Result_code", "resultCode", "ResultCode"):
            if key in value:
                return str(value[key]).strip()
        return ""

    def prepare(
        self,
        servers: dict[str, str],
        profile: dict[str, str],
    ) -> dict[str, Any]:
        register = self._post(
            _https_url(servers.get("ews", ""), UC_REGISTER_PATH),
            build_uc_registration(profile),
        )
        register_code = self._result_code(register)
        if register_code and register_code not in {"1", "8", "32", "200"}:
            raise PermissionError(f"COMMAX UC registration rejected ({register_code})")

        lookup = self._post(
            _https_url(servers.get("ns", ""), UC_LOOKUP_PATH),
            {"getCSAddressByUserId": profile["id"]},
        )
        lookup_code = self._result_code(lookup)
        if lookup_code and lookup_code not in {"1", "8", "32", "200"}:
            raise PermissionError(f"COMMAX UC server lookup rejected ({lookup_code})")
        sip_server = str(
            lookup.get("Response")
            or lookup.get("response")
            or lookup.get("server")
            or ""
        ).strip()
        if not sip_server:
            raise ValueError("COMMAX UC lookup did not return a SIP server")
        return {
            "registered": True,
            "sipServer": sip_server,
            "registeredAt": int(time.time()),
            "registerCode": register_code or "http_ok",
            "lookupCode": lookup_code or "http_ok",
        }
