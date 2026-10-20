"""Authenticated COMMAX cloud transport used by the official Android app.

The account password is used only for the OAuth exchange and is never written
to disk. The resulting access/refresh session and device inventory are stored
in a mode-0600 JSON file so the gateway can refresh the session after reboot.
"""

from __future__ import annotations

import base64
import hashlib
import html
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any

from official_uc import ModernUcCompatibility, OfficialUcBootstrap, derive_uc_profile


AUTH_ROOT = "https://biz.ruvie.co.kr:4000"
CLIENT_ID = "APP-AND-com.commax.ipiot"
# Set locally; never commit application credentials to a public repository.
CLIENT_SECRET = os.environ.get("HOMEPAD_COMMAX_CLIENT_SECRET", "")
PORTAL_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"
)


def _first(value: Any, key: str) -> Any:
    if isinstance(value, dict):
        if key in value:
            return value[key]
        for child in value.values():
            found = _first(child, key)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _first(child, key)
            if found is not None:
                return found
    return None


def _server_url(value: Any) -> str:
    if isinstance(value, str):
        return value.rstrip("/")
    if not isinstance(value, dict):
        return ""
    address = str(value.get("ip") or value.get("host") or "").strip()
    if not address:
        return ""
    if address.startswith(("http://", "https://")):
        base = address
    else:
        scheme = "https" if str(value.get("ssl", "")).upper() in {"Y", "YES", "TRUE", "1"} else "http"
        base = f"{scheme}://{address}"
    port = value.get("port")
    if port and ":" not in urllib.parse.urlparse(base).netloc:
        base += f":{int(port)}"
    return base.rstrip("/")


class OfficialCommaxClient:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.enabled = bool(config.get("enabled", False))
        self.session_path = Path(config.get("sessionPath", "/opt/homepad-agent/state/commax-official-session.json"))
        self.timeout = max(3.0, min(20.0, float(config.get("timeoutSeconds", 10))))
        self.lock = threading.RLock()
        self.session: dict[str, Any] = {}
        self._load()

    def _load(self) -> None:
        if not self.session_path.is_file():
            return
        try:
            value = json.loads(self.session_path.read_text(encoding="utf-8"))
            if isinstance(value, dict):
                self.session = value
        except (OSError, ValueError, TypeError):
            self.session = {}

    def _save(self) -> None:
        self.session_path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.session_path.with_suffix(self.session_path.suffix + ".tmp")
        temp.write_text(json.dumps(self.session, ensure_ascii=False), encoding="utf-8")
        try:
            os.chmod(temp, 0o600)
        except OSError:
            pass
        os.replace(temp, self.session_path)
        try:
            os.chmod(self.session_path, 0o600)
        except OSError:
            pass

    def _request(
        self,
        url: str,
        headers: dict[str, str] | None = None,
        data: dict[str, Any] | None = None,
        method: str | None = None,
    ) -> dict[str, Any]:
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        request_headers = {"Accept": "application/json", **(headers or {})}
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            url,
            data=body,
            headers=request_headers,
            method=method or ("POST" if body is not None else "GET"),
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read(2 * 1024 * 1024)
        except urllib.error.HTTPError as exc:
            detail = exc.read(4096).decode("utf-8", "replace")
            raise OSError(f"COMMAX HTTP {exc.code}: {detail[:256]}") from exc
        value = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(value, dict):
            raise ValueError("COMMAX response was not a JSON object")
        return value

    def _device_headers(self, device_uuid: str) -> dict[str, str]:
        return {"cmx-dvc-os": "AND", "cmx-dvc-uuid": device_uuid, "cmx-app-version": "0.0.0.1"}

    def _auth_headers(self) -> dict[str, str]:
        token_type = str(self.session.get("tokenType") or "Bearer")
        token = str(self.session.get("accessToken") or "")
        if not token:
            raise PermissionError("COMMAX official session is not connected")
        return {"Content-Type": "application/json", "Authorization": f"{token_type} {token}"}

    def enroll(self, username: str, password: str, device_uuid: str = "") -> dict[str, Any]:
        if not self.enabled:
            raise PermissionError("COMMAX official integration is disabled")
        username = username.strip()
        if not username or not password:
            raise ValueError("COMMAX ID and password are required")
        device_uuid = device_uuid.strip() or uuid.uuid4().hex
        query = urllib.parse.urlencode(
            {
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
                "grant_type": "password",
                "username": username,
                "password": password,
            }
        )
        auth = self._request(f"{AUTH_ROOT}/oauth/authorize?{query}", self._device_headers(device_uuid))
        access_token = _first(auth, "access_token")
        refresh_token = _first(auth, "refresh_token")
        token_type = _first(auth, "token_type") or "Bearer"
        if not access_token or not refresh_token:
            raise PermissionError("COMMAX login did not return an OAuth session")
        server = _first(auth, "server")
        iot_value = server.get("iot") if isinstance(server, dict) else _first(auth, "iotServer")
        iot_server = _server_url(iot_value)
        if not iot_server:
            raise ValueError("COMMAX login did not return an IoT server")
        expires = int(_first(auth, "expire_in") or _first(auth, "expires_in") or 3600)
        with self.lock:
            access_value = server.get("access") if isinstance(server, dict) else None
            uc_value = auth.get("ucServer") if isinstance(auth, dict) else None
            uc_servers = {
                key: str(uc_value.get(key) or "").strip()
                for key in ("ns", "ews", "turn", "stun")
            } if isinstance(uc_value, dict) else {}
            self.session = {
                "username": username,
                "deviceUuid": device_uuid,
                "tokenType": str(token_type),
                "accessToken": str(access_token),
                "refreshToken": str(refresh_token),
                "expiresAt": int(time.time()) + max(60, expires - 60),
                "iotServer": iot_server,
                "accessServer": _server_url(access_value),
                "userNo": str(_first(auth, "userNo") or ""),
                "uc": {"servers": uc_servers},
                "connectedAt": int(time.time()),
            }
            self._discover_user_locked()
            self._discover_locked()
            self._save()
            return self.summary(include_devices=True)

    def _discover_user_locked(self) -> None:
        access = str(self.session.get("accessServer") or "").rstrip("/")
        if not access:
            return
        user = self._request(access + "/v1/user/home", self._auth_headers())
        residence = {
            key: str(_first(user, key) or "").strip()
            for key in ("buildingNo", "roomNo", "centerCode", "resourceNo", "centerIp")
        }
        self.session["residence"] = residence

    @staticmethod
    def _portal_access_header(
        building_no: str,
        room_no: str,
        center_code: str,
        app_version: int = 0,
    ) -> str:
        """Reproduce the official WebView's app-independent portal header."""
        building = str(building_no).strip().zfill(4)
        room = str(room_no).strip().zfill(4)
        plain = f"{building}|{room}|{center_code}|CMXMOBILE|AOS|{int(app_version)}"
        encoded = base64.b64encode(plain.encode("utf-8")).decode("ascii")
        disguised = encoded[:3] + "8" + encoded[3:9] + "C" + encoded[9:17] + "u" + encoded[17:]
        return urllib.parse.quote(disguised, safe="")

    def _portal_request(
        self,
        opener: urllib.request.OpenerDirector,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        data: dict[str, Any] | None = None,
    ) -> str:
        body = None if data is None else urllib.parse.urlencode(data).encode("utf-8")
        request_headers = {
            "User-Agent": PORTAL_USER_AGENT,
            "X-Requested-With": "com.commax.ipiot",
            **(headers or {}),
        }
        if body is not None:
            request_headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(url, data=body, headers=request_headers)
        try:
            with opener.open(request, timeout=self.timeout) as response:
                raw = response.read(2 * 1024 * 1024)
        except urllib.error.HTTPError as exc:
            detail = exc.read(4096).decode("utf-8", "replace")
            raise OSError(f"COMMAX portal HTTP {exc.code}: {detail[:160]}") from exc
        return raw.decode("utf-8", "replace")

    @staticmethod
    def _parse_parking_events(value: str) -> list[dict[str, str]]:
        events: list[dict[str, str]] = []
        pattern = re.compile(
            r"\[\s*<font[^>]*>(.*?)</font>\s*\]\s*"
            r"(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s*(입차|출차)",
            re.IGNORECASE,
        )
        for plate_html, occurred_at, movement in pattern.findall(value):
            plate = re.sub(r"<[^>]+>", "", html.unescape(plate_html)).strip()
            if plate:
                events.append(
                    {"carNumber": plate, "occurredAt": occurred_at.strip(), "movement": movement}
                )
        return events

    @staticmethod
    def _normalize_parking_events(
        events: list[dict[str, str]], limit: int
    ) -> list[dict[str, Any]]:
        """Pair each exit with the immediately preceding entry for that vehicle.

        The apartment portal normally returns newest rows first, but some portal
        versions return the initial page and AJAX pages in different orders.  Sort
        the complete event set before pairing and use a normalized plate key so a
        formatted plate (spaces or a dash) still matches the corresponding exit.
        """
        ordered = sorted(
            events,
            key=lambda item: re.sub(r"\D", "", str(item.get("occurredAt") or "")),
            reverse=True,
        )
        pending_exit: dict[str, list[str]] = {}
        result: list[dict[str, Any]] = []
        for event in ordered:
            plate = str(event["carNumber"]).strip()
            plate_key = re.sub(r"[^0-9A-Za-z가-힣]", "", plate).casefold()
            occurred_at = str(event["occurredAt"]).strip()
            if event["movement"] == "출차":
                pending_exit.setdefault(plate_key, []).append(occurred_at)
                continue
            exits = pending_exit.get(plate_key, [])
            exit_at = exits.pop(0) if exits else ""
            if not exits:
                pending_exit.pop(plate_key, None)
            identity = f"{plate}|{occurred_at}"
            version_identity = f"{identity}|{exit_at}"
            result.append(
                {
                    "id": hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24],
                    "notificationId": hashlib.sha256(
                        version_identity.encode("utf-8")
                    ).hexdigest()[:24],
                    "carNumber": plate,
                    "vehicleType": "registered",
                    "vehicleTypeLabel": "세대차량",
                    "status": "출차" if exit_at else "입차",
                    "entryAt": occurred_at,
                    "exitAt": exit_at,
                    "source": "commax_portal",
                    "sourceLabel": "코맥스 IoT",
                }
            )
        result.sort(
            key=lambda item: re.sub(r"\D", "", str(item.get("entryAt") or "")),
            reverse=True,
        )
        return result[:limit]

    def parking_activity(self, limit: int = 7) -> dict[str, Any]:
        """Read household vehicle activity without the COMMAX Android package."""
        if not self.enabled:
            raise PermissionError("COMMAX official integration is disabled")
        limit = max(1, min(50, int(limit)))
        with self.lock:
            self._ensure_session_locked()
            residence = self.session.get("residence")
            if not isinstance(residence, dict) or not all(
                residence.get(key) for key in ("buildingNo", "roomNo", "centerCode", "centerIp")
            ):
                self._discover_user_locked()
                self._save()
                residence = self.session.get("residence", {})

            center_ip = str(residence.get("centerIp") or "").strip()
            building = str(residence.get("buildingNo") or "").strip()
            room = str(residence.get("roomNo") or "").strip()
            center_code = str(residence.get("centerCode") or "").strip()
            if not center_ip or not building or not room or not center_code:
                raise ValueError("COMMAX portal residence information is incomplete")
            root = center_ip if center_ip.startswith(("http://", "https://")) else f"http://{center_ip}"
            root = root.rstrip("/")
            parsed_root = urllib.parse.urlparse(root)
            if (
                parsed_root.scheme not in {"http", "https"}
                or not parsed_root.hostname
                or parsed_root.username
                or parsed_root.password
                or not re.fullmatch(r"[A-Za-z0-9.-]+", parsed_root.hostname)
            ):
                raise ValueError("COMMAX portal address is invalid")
            query = {
                "code": "",
                "mac": "",
                "dong": building,
                "ho": room,
                "wpip": "",
                "deip": "",
            }
            main_query = {**query, "mobile": "y"}
            main_url = root + "/m/index.php?" + urllib.parse.urlencode(main_query)
            app_version = int(self.config.get("portalAppVersion", 0))
            portal_header = self._portal_access_header(building, room, center_code, app_version)
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
            main_html = self._portal_request(
                opener,
                main_url,
                headers={"X-CPTL1": portal_header},
            )
            if "주차로그" not in main_html:
                raise PermissionError("COMMAX portal session was not accepted")

            car_query = {**query, "code": center_code}
            car_url = root + "/m/car/index.php?" + urllib.parse.urlencode(car_query)
            car_html = self._portal_request(opener, car_url, headers={"Referer": main_url})
            events = self._parse_parking_events(car_html)
            total_match = re.search(r'id=["\']tot_cnt["\']\s+value=["\'](\d+)', car_html)
            total = int(total_match.group(1)) if total_match else len(events)

            scan_limit = min(total, max(10, min(50, limit * 5)))
            offset = 5
            ajax_url = root + "/m/car/ajax_list.php"
            while len(events) < scan_limit and offset < total:
                batch_html = self._portal_request(
                    opener,
                    ajax_url,
                    headers={"Referer": car_url},
                    data={**car_query, "cnt": 5, "s_red": offset},
                )
                batch = self._parse_parking_events(batch_html)
                if not batch:
                    break
                events.extend(batch)
                offset += 5

            unique_events: list[dict[str, str]] = []
            seen: set[tuple[str, str, str]] = set()
            for event in events:
                key = (event["carNumber"], event["occurredAt"], event["movement"])
                if key not in seen:
                    seen.add(key)
                    unique_events.append(event)
            activity = self._normalize_parking_events(unique_events, limit)
            aliases = self.config.get(
                "vehicleAliases",
                {},
            )
            if not isinstance(aliases, dict):
                aliases = {}
            for item in activity:
                plate = str(item.get("carNumber") or "")
                vehicle_name = str(aliases.get(plate) or "").strip()
                if vehicle_name:
                    item["vehicleName"] = vehicle_name
                    item["displayCarNumber"] = f"{plate}({vehicle_name})"
            return {
                "activity": activity,
                "total": total,
                "limit": limit,
                "source": "commax_portal",
                "sourceLabel": "코맥스 IoT",
                "appPackageRequired": False,
            }

    def _refresh_locked(self) -> None:
        query = urllib.parse.urlencode(
            {
                "client_id": CLIENT_ID,
                "client_secret": CLIENT_SECRET,
                "grant_type": "refresh_token",
                "refresh_token": self.session.get("refreshToken", ""),
            }
        )
        device_uuid = str(self.session.get("deviceUuid") or "").strip()
        if not device_uuid:
            raise PermissionError("COMMAX session has no device identifier")
        value = self._request(
            f"{AUTH_ROOT}/oauth/token?{query}",
            self._device_headers(device_uuid),
        )
        access = _first(value, "access_token")
        if not access:
            raise PermissionError("COMMAX token refresh failed")
        self.session["accessToken"] = str(access)
        refresh = _first(value, "refresh_token")
        if refresh:
            self.session["refreshToken"] = str(refresh)
        expires = int(_first(value, "expire_in") or _first(value, "expires_in") or 3600)
        self.session["expiresAt"] = int(time.time()) + max(60, expires - 60)
        self._save()

    def _ensure_session_locked(self) -> None:
        if not self.session.get("accessToken"):
            raise PermissionError("COMMAX official session is not connected")
        if int(self.session.get("expiresAt", 0)) <= int(time.time()):
            self._refresh_locked()

    def _discover_locked(self) -> None:
        headers = self._auth_headers()
        iot = str(self.session["iotServer"])
        gateways = self._request(iot + "/v1/gateways", headers)
        gateway_no = _first(gateways, "gatewayNo")
        if not gateway_no:
            raise ValueError("COMMAX account has no gateway")
        detail = self._request(iot + "/v1/gateways/" + urllib.parse.quote(str(gateway_no), safe=""), headers)
        self.session["gatewayNo"] = str(gateway_no)
        self.session["inventory"] = detail

    def _refresh_inventory_locked(self) -> None:
        """Refresh the existing gateway inventory with one authenticated read."""
        gateway_no = str(self.session.get("gatewayNo") or "").strip()
        if not gateway_no:
            self._discover_locked()
            return
        iot = str(self.session["iotServer"])
        detail = self._request(
            iot + "/v1/gateways/" + urllib.parse.quote(gateway_no, safe=""),
            self._auth_headers(),
        )
        self.session["inventory"] = detail

    def refresh_inventory(self) -> dict[str, Any]:
        with self.lock:
            self._ensure_session_locked()
            self._refresh_inventory_locked()
            self._save()
            return self.summary(include_devices=True)

    def refresh_devices(self) -> list[dict[str, Any]]:
        """Return a fresh, secret-free flattened device inventory."""
        with self.lock:
            self._ensure_session_locked()
            self._refresh_inventory_locked()
            self._save()
            return self._device_candidates()

    def _device_candidates(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []

        def walk(value: Any, parent: dict[str, Any] | None = None) -> None:
            if isinstance(value, dict):
                current = value if "rootUuid" in value else parent
                if "subUuid" in value and current:
                    item = {
                        "rootUuid": current.get("rootUuid"),
                        "rootDevice": current.get("rootDevice"),
                        "nickname": current.get("nickname") or current.get("name"),
                        "subUuid": value.get("subUuid"),
                        "subDevice": value.get("subDevice") or value.get("name"),
                        "type": value.get("type"),
                        "sort": value.get("sort"),
                        "value": value.get("value"),
                    }
                    # The official Android client sends these fields when they
                    # are present (notably thermostat setpoint bounds/scale).
                    for key in ("option1", "option2", "precision", "scale"):
                        if value.get(key) not in (None, "", "-1"):
                            item[key] = value.get(key)
                    result.append(item)
                for child in value.values():
                    walk(child, current)
            elif isinstance(value, list):
                for child in value:
                    walk(child, parent)

        walk(self.session.get("inventory", {}))
        unique: dict[tuple[Any, Any], dict[str, Any]] = {}
        for item in result:
            unique[(item.get("rootUuid"), item.get("subUuid"))] = item
        return list(unique.values())

    def _find_device_locked(self, nickname: str, root_device: str, sort: str) -> dict[str, Any]:
        matches = [
            item
            for item in self._device_candidates()
            if item.get("nickname") == nickname
            and item.get("rootDevice") == root_device
            and item.get("sort") == sort
            and item.get("type") == "readWrite"
            and item.get("rootUuid")
            and item.get("subUuid")
        ]
        if len(matches) != 1:
            raise LookupError(
                f"COMMAX device mapping must be unique: {nickname}/{root_device}/{sort} ({len(matches)} matches)"
            )
        return matches[0]

    def _post_command_locked(self, item: dict[str, Any], value: str) -> dict[str, Any]:
        sub_device = {
            "subUuid": item["subUuid"],
            "type": item["type"],
            "sort": item["sort"],
            "funcCommand": "set",
            "value": value,
        }
        for key in ("option1", "option2", "precision"):
            if item.get(key) not in (None, "", "-1"):
                sub_device[key] = item[key]
        body = {
            "commands": {
                "cgpCommand": [
                    {
                        "gatewayNo": self.session["gatewayNo"],
                        "cgp": {
                            "command": "set",
                            "object": {
                                "rootUuid": item["rootUuid"],
                                "rootDevice": item["rootDevice"],
                                "nickname": item["nickname"],
                                "subDevice": [sub_device],
                            },
                        },
                    }
                ]
            }
        }
        url = str(self.session["iotServer"]) + "/v1/command"
        try:
            response = self._request(url, self._auth_headers(), body)
        except OSError as exc:
            # Access tokens are short-lived. Refresh once on a definite auth
            # rejection; ambiguous network errors are returned without a
            # local EW11 retry so the physical action cannot be duplicated.
            if "COMMAX HTTP 401:" not in str(exc):
                raise
            self._refresh_locked()
            response = self._request(url, self._auth_headers(), body)
        return {
            "accepted": True,
            "source": "official_app",
            "nickname": item["nickname"],
            "rootDevice": item["rootDevice"],
            "sort": item["sort"],
            "requestedValue": value,
            "responseKeys": sorted(response.keys()),
        }

    def set_value(self, nickname: str, root_device: str, sort: str, value: str) -> dict[str, Any]:
        if not self.enabled or not self.config.get("controlEnabled", False):
            raise PermissionError("COMMAX official control is disabled")
        with self.lock:
            self._ensure_session_locked()
            item = self._find_device_locked(nickname, root_device, sort)
            return self._post_command_locked(item, str(value))

    def set_light(self, nickname: str, enabled: bool) -> dict[str, Any]:
        return self.set_value(nickname, "switch", "switchBinary", "on" if enabled else "off")

    def set_heating_power(self, nickname: str, enabled: bool) -> dict[str, Any]:
        return self.set_value(nickname, "thermostat", "thermostatMode", "heat" if enabled else "off")

    def set_heating_setpoint(self, nickname: str, temperature: int) -> dict[str, Any]:
        return self.set_value(nickname, "thermostat", "thermostatSetpoint", str(int(temperature)))

    def set_batch_switch(self, nickname: str, enabled: bool) -> dict[str, Any]:
        return self.set_value(nickname, "switch", "switchBinary", "on" if enabled else "off")

    def elevator_capability(self) -> dict[str, Any]:
        """Describe the official app elevator endpoint without exposing credentials.

        The compatible app's endpoint calls the elevator for the registered
        residence.  It accepts no floor or direction body, and its response is
        only a result code/message.  Therefore it is not a destination source
        and cannot be used to request B2.
        """
        residence = (
            self.session.get("residence")
            if isinstance(self.session.get("residence"), dict)
            else {}
        )
        resource_no = str(residence.get("resourceNo") or "").strip()
        iot_server = str(self.session.get("iotServer") or "").strip()
        return {
            "available": bool(
                self.enabled
                and self.session.get("accessToken")
                and iot_server
                and resource_no
            ),
            "enabled": bool(self.config.get("elevatorCallEnabled", False)),
            "verified": False,
            "source": "official_app",
            "target": "registered_residence",
            "supportsTargetFloor": False,
            "supportsB2": False,
            "endpoint": "/v2/resource/{resourceNo}/elevatorCallLS",
            "reason": "공식 앱 요청에는 층·방향·목적층 인수가 없고 등록 세대 resourceNo만 사용함",
        }

    def call_elevator(self) -> dict[str, Any]:
        """Call only the account's registered residence through the official API.

        This path is opt-in because it has not yet been field-verified for this
        apartment.  It intentionally accepts no target-floor argument.
        """
        if not self.enabled:
            raise PermissionError("COMMAX official integration is disabled")
        if not self.config.get("elevatorCallEnabled", False):
            raise PermissionError("COMMAX official elevator call is disabled")
        with self.lock:
            self._ensure_session_locked()
            residence = self.session.get("residence")
            if not isinstance(residence, dict) or not residence.get("resourceNo"):
                self._discover_user_locked()
                self._save()
                residence = self.session.get("residence", {})
            resource_no = str(residence.get("resourceNo") or "").strip()
            root = str(self.session.get("iotServer") or "").rstrip("/")
            if not root or not resource_no:
                raise ValueError("COMMAX elevator residence information is incomplete")
            url = (
                root
                + "/v2/resource/"
                + urllib.parse.quote(resource_no, safe="")
                + "/elevatorCallLS"
            )
            try:
                value = self._request(
                    url,
                    self._auth_headers(),
                    method="POST",
                )
            except OSError as exc:
                if "COMMAX HTTP 401:" not in str(exc):
                    raise
                self._refresh_locked()
                value = self._request(
                    url,
                    self._auth_headers(),
                    method="POST",
                )
            return {
                "source": "official_app",
                "target": "registered_residence",
                "supportsTargetFloor": False,
                "supportsB2": False,
                "response": value,
            }

    def uc_summary(self) -> dict[str, Any]:
        uc = self.session.get("uc") if isinstance(self.session.get("uc"), dict) else {}
        compatibility = (
            uc.get("compatibility")
            if isinstance(uc.get("compatibility"), dict)
            else {}
        )
        servers = uc.get("servers") if isinstance(uc.get("servers"), dict) else {}
        residence = self.session.get("residence") if isinstance(self.session.get("residence"), dict) else {}
        missing = [
            key
            for key in ("buildingNo", "roomNo", "centerCode", "resourceNo")
            if not residence.get(key)
        ]
        missing += [key for key in ("ews", "ns") if not servers.get(key)]
        if missing:
            transport_reason = "UC 계정 정보가 준비되지 않음"
        elif (
            compatibility.get("structuralCompatible")
            and not compatibility.get("credentialCompatible")
        ):
            transport_reason = (
                "최신 UC 통화 구조는 호환되지만 현재 호환 앱의 Ruvie 토큰을 "
                "최신 UC 서버가 승인하지 않음"
            )
        elif not uc.get("registered"):
            transport_reason = "UC HTTP 등록이 완료되지 않음"
        else:
            transport_reason = "UC 계정은 준비됐지만 활성 통화 SIP 중계기가 아직 연결되지 않음"
        return {
            "metadataReady": not missing,
            "missing": missing,
            "registered": bool(uc.get("registered")),
            "sipServerReady": bool(uc.get("sipServer")),
            "registeredAt": uc.get("registeredAt"),
            "compatibility": compatibility,
            "transportReady": False,
            "transportReason": transport_reason,
        }

    def prepare_uc(self) -> dict[str, Any]:
        """Probe UC v2, then use the compatible legacy bootstrap as fallback."""
        if not self.enabled:
            raise PermissionError("COMMAX official integration is disabled")
        with self.lock:
            self._ensure_session_locked()
            uc = self.session.get("uc") if isinstance(self.session.get("uc"), dict) else {}
            compatibility = ModernUcCompatibility(self.timeout).probe(self.session)
            uc["compatibility"] = compatibility
            self.session["uc"] = uc
            self._save()
            if compatibility.get("credentialCompatible"):
                # The modern Android UC engine is structurally reusable, but
                # this server bridge must not claim SIP readiness until its
                # registration transport has been implemented and observed.
                return self.uc_summary()
            servers = uc.get("servers") if isinstance(uc.get("servers"), dict) else {}
            residence = self.session.get("residence") if isinstance(self.session.get("residence"), dict) else {}
            profile = derive_uc_profile(residence, str(self.session.get("deviceUuid") or ""))
            result = OfficialUcBootstrap(self.timeout).prepare(servers, profile)
            # Store only registration metadata.  UC and door passwords are
            # always derived or supplied in memory and are never persisted.
            uc.update(result)
            self.session["uc"] = uc
            self._save()
            return self.uc_summary()

    def summary(self, include_devices: bool = False) -> dict[str, Any]:
        with self.lock:
            value: dict[str, Any] = {
                "enabled": self.enabled,
                "connected": bool(self.session.get("accessToken")),
                "username": self.session.get("username", ""),
                "gatewayNo": self.session.get("gatewayNo", ""),
                "connectedAt": self.session.get("connectedAt"),
                "expiresAt": self.session.get("expiresAt"),
                "deviceCount": len(self._device_candidates()),
                "uc": self.uc_summary(),
                "elevator": self.elevator_capability(),
            }
            if include_devices:
                value["devices"] = self._device_candidates()
            return value
