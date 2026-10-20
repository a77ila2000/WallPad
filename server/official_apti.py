"""Authenticated Apartmenti visitor-parking integration.

The account password is used only for the login exchange and is never written
to disk.  The resulting mobile token is stored in a mode-0600 session file.
"""

from __future__ import annotations

import json
import hashlib
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any


MAIN_V2_ROOT = "https://api-main.apti.co.kr/api/v2/"
MAIN_V3_SDI_ROOT = "https://api-main.apti.co.kr/v3/sdi/"
PARKING_ROOT = "https://api-car.apti.co.kr/api/"


def _find(value: Any, key: str) -> Any:
    if isinstance(value, dict):
        if key in value:
            return value[key]
        for child in value.values():
            found = _find(child, key)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _find(child, key)
            if found is not None:
                return found
    return None


def _reservation_items(value: Any) -> list[dict[str, Any]]:
    """Return the first list that looks like the official reservation history."""
    if isinstance(value, list):
        if not value or any(
            isinstance(item, dict)
            and ("carNoInformation" in item or "reservationVisitCarId" in item)
            for item in value
        ):
            return [item for item in value if isinstance(item, dict)]
        for child in value:
            found = _reservation_items(child)
            if found:
                return found
    elif isinstance(value, dict):
        preferred = (
            "reservationVisitCarList",
            "visitCarList",
            "carListResDtoList",
            "list",
            "items",
        )
        for key in preferred:
            child = value.get(key)
            if isinstance(child, list):
                found = _reservation_items(child)
                if found or not child:
                    return found
        for child in value.values():
            found = _reservation_items(child)
            if found:
                return found
    return []


class AptiParkingClient:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.enabled = bool(config.get("enabled", False))
        self.session_path = Path(
            config.get(
                "sessionPath",
                "/opt/homepad-agent/state/apti-parking-session.json",
            )
        )
        self.cache_path = Path(
            config.get(
                "parkingCachePath",
                "/opt/homepad-agent/state/apti-parking-cache.json",
            )
        )
        self.monthly_allowance_minutes = max(
            60, int(config.get("monthlyAllowanceMinutes", 100 * 60))
        )
        self.timeout = max(3.0, min(20.0, float(config.get("timeoutSeconds", 10))))
        self.app_version = str(config.get("appVersion", "3.3.62"))
        self.main_root = str(config.get("mainV2ApiUrl", MAIN_V2_ROOT)).rstrip("/") + "/"
        self.main_v3_sdi_root = str(
            config.get("mainV3SdiApiUrl", MAIN_V3_SDI_ROOT)
        ).rstrip("/") + "/"
        self.parking_root = str(config.get("parkingApiUrl", PARKING_ROOT)).rstrip("/") + "/"
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

    @staticmethod
    def _write_private_json(path: Path, value: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(path.suffix + ".tmp")
        temp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        try:
            os.chmod(temp, 0o600)
        except OSError:
            pass
        os.replace(temp, path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass

    def _load_dashboard_cache(self) -> dict[str, Any]:
        try:
            value = json.loads(self.cache_path.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError, TypeError):
            return {}

    def _save_dashboard_cache(self, value: dict[str, Any]) -> None:
        self._write_private_json(self.cache_path, value)

    def _mark_parking_result_locked(self, error: str = "") -> None:
        if error:
            self.session["parkingApiVerified"] = False
            self.session["parkingApiError"] = error[:256]
            self.session["parkingApiCheckedAt"] = int(time.time())
        else:
            now = int(time.time())
            self.session["parkingApiVerified"] = True
            self.session["parkingApiError"] = ""
            self.session["parkingApiCheckedAt"] = now
            self.session["parkingApiSuccessAt"] = now
        self._save()

    def _request(
        self,
        url: str,
        method: str = "GET",
        headers: dict[str, str] | None = None,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        body = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
        request_headers = {
            "Accept": "application/json",
            # The mobile API rejects an arbitrary agent string with an HTML
            # 403, even when the mobile token is valid.
            "User-Agent": "Dart/3.9 (dart:io)",
            **(headers or {}),
        }
        if body is not None:
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(
            url,
            data=body,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read(2 * 1024 * 1024)
        except urllib.error.HTTPError as exc:
            raw = exc.read(65536)
            detail = raw.decode("utf-8", "replace")
            json_error = False
            try:
                value = json.loads(detail)
                if isinstance(value, dict):
                    json_error = True
                    detail = str(value.get("message") or value.get("description") or detail)
            except (ValueError, TypeError):
                pass
            if exc.code in (401, 403) and json_error:
                raise PermissionError("아파트아이 로그인이 만료되었습니다. 다시 연결해 주세요") from exc
            if exc.code in (401, 403):
                # A gateway denial is not an account rejection. Re-enrolling
                # here can invalidate the official app's still-valid session.
                raise OSError(f"아파트아이 서버 접속이 거절되었습니다 (HTTP {exc.code}). 연결 경로를 확인해 주세요") from exc
            raise OSError(f"아파트아이 HTTP {exc.code}: {detail[:256]}") from exc
        value = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(value, dict):
            raise ValueError("아파트아이 응답이 JSON 객체가 아닙니다")
        return value

    def _device_id_locked(self) -> str:
        value = str(self.session.get("adid") or "").strip()
        if not value:
            value = str(uuid.uuid4())
            self.session["adid"] = value
        return value

    def _login_headers_locked(self) -> dict[str, str]:
        adid = self._device_id_locked()
        return {
            "app-version": self.app_version,
            "adid": adid,
            "adid-idfa": adid,
            "push-token": "",
            "finger-push-token": "",
        }

    def _parking_headers_locked(self) -> dict[str, str]:
        token = str(self.session.get("mblToken") or "")
        if not token:
            raise PermissionError("아파트아이 계정을 먼저 연결해 주세요")
        adid = self._device_id_locked()
        return {
            "mbl-token": token,
            "app-version": self.app_version,
            "adid": adid,
        }

    def _login_locked(self, account: str, password: str, method: str) -> dict[str, Any]:
        value = self._request(
            urllib.parse.urljoin(self.main_root, f"login/{method}"),
            method="POST",
            headers=self._login_headers_locked(),
            data={
                "id": account,
                "password": password if method != "naver" else None,
            },
        )
        token = _find(value, "mblToken")
        if not token:
            message = _find(value, "message") or "로그인 응답에 mblToken이 없습니다"
            raise PermissionError(str(message))
        self.session.update(
            {
                "username": account,
                "loginMethod": method,
                "mblToken": str(token),
                "userId": str(_find(value, "userId") or ""),
                "connectedAt": int(time.time()),
            }
        )
        self._save()
        return value

    def _parking_request_locked(
        self,
        url: str,
        method: str = "GET",
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        def request_once() -> dict[str, Any]:
            return self._request(
                url,
                method=method,
                headers=self._parking_headers_locked(),
                data=data,
            )

        try:
            value = request_once()
            self._mark_parking_result_locked()
            return value
        except (PermissionError, OSError) as first_error:
            # The parking service sometimes reports an invalidated Naver token
            # as HTTP 500 instead of 401/403 after the official app logs in.
            # Refresh the independently persisted server session once.
            refreshable = isinstance(first_error, PermissionError) or (
                "API 호출 정보가 존재하지 않음" in str(first_error)
            )
            if (
                not refreshable
                or self.session.get("loginMethod") != "naver"
                or not self.session.get("username")
            ):
                self._mark_parking_result_locked(str(first_error))
                raise
            try:
                self._login_locked(str(self.session["username"]), "", "naver")
                value = request_once()
                self._mark_parking_result_locked()
                return value
            except (PermissionError, OSError) as retry_error:
                self._mark_parking_result_locked(str(retry_error))
                raise

    def enroll(self, account: str, password: str, method: str = "id") -> dict[str, Any]:
        if not self.enabled:
            raise PermissionError("아파트아이 주차 연동이 비활성화되어 있습니다")
        method = method.strip().lower()
        if method not in {"id", "phone", "naver"}:
            raise ValueError("지원하지 않는 아파트아이 로그인 방식입니다")
        account = account.strip()
        if method == "phone":
            account = re.sub(r"\D", "", account)
        if not account or (method in {"id", "phone"} and not password):
            raise ValueError("아파트아이 로그인 정보를 확인해 주세요")
        with self.lock:
            self._login_locked(account, password, method)
            return self.summary()

    def summary(self) -> dict[str, Any]:
        with self.lock:
            return {
                "enabled": self.enabled,
                "connected": bool(self.session.get("mblToken")),
                "username": str(self.session.get("username") or ""),
                "loginMethod": str(self.session.get("loginMethod") or ""),
                "connectedAt": int(self.session.get("connectedAt") or 0),
                "apiVerified": bool(self.session.get("parkingApiVerified", False)),
                "apiError": str(self.session.get("parkingApiError") or ""),
                "apiCheckedAt": int(self.session.get("parkingApiCheckedAt") or 0),
                "apiSuccessAt": int(self.session.get("parkingApiSuccessAt") or 0),
            }

    def history(self, based_month: str) -> dict[str, Any]:
        if not re.fullmatch(r"\d{6}", based_month):
            raise ValueError("month must use YYYYMM")
        with self.lock:
            query = urllib.parse.urlencode({"basedMonth": based_month})
            value = self._parking_request_locked(
                urllib.parse.urljoin(self.parking_root, "parking/v2/visit") + "?" + query
            )
        return {
            "month": based_month,
            "reservations": _reservation_items(value),
            "policy": {
                key: value.get(key)
                for key in (
                    "carReservationPolicy",
                    "freeParkingHour",
                    "remainTime",
                    "parkedTime",
                    "discountTime",
                    "exceedTime",
                    "expectedParkingFee",
                )
                if key in value
            },
        }

    def registered_vehicles(self) -> list[dict[str, Any]]:
        """Return household vehicles registered in the official Apartmenti account."""
        with self.lock:
            value = self._parking_request_locked(
                urllib.parse.urljoin(self.main_v3_sdi_root, "household-info")
            )
        cars = _find(value, "cars")
        if not isinstance(cars, list):
            return []
        return [item for item in cars if isinstance(item, dict)]

    @staticmethod
    def _month_offset(offset: int) -> str:
        today = date.today()
        total = today.year * 12 + today.month - 1 - offset
        return f"{total // 12:04d}{total % 12 + 1:02d}"

    @staticmethod
    def _event_value(event: dict[str, Any], *names: str) -> str:
        for name in names:
            value = event.get(name)
            if value is not None and str(value).strip():
                return str(value).strip()
        return ""

    @classmethod
    def _normalize_activity(
        cls,
        reservations: list[dict[str, Any]],
        registered_plates: set[str],
    ) -> list[dict[str, Any]]:
        """Return exactly one row for each Apartmenti visit reservation.

        The movement list also contains the reservation event.  Treating that
        event as an entry creates a duplicate open stay beside the real
        entry/exit pair, so prefer the explicit carInDate/carOutDate fields and
        use movement records only as a compatibility fallback.
        """
        activity: list[dict[str, Any]] = []
        for item in reservations:
            plate = re.sub(
                r"\s",
                "",
                str(
                    item.get("carNoInformation")
                    or item.get("carNumber")
                    or item.get("visitCarNo")
                    or item.get("carNo")
                    or item.get("vehicleNo")
                    or ""
                ),
            )
            visit_type = str(item.get("visitType") or "").upper()
            registered = visit_type == "O" or plate in registered_plates
            entry_at = cls._event_value(
                item, "carInDate", "entryAt", "inDate", "carEntryDate"
            )
            exit_at = cls._event_value(
                item, "carOutDate", "exitAt", "outDate", "carExitDate"
            )
            registered_at = cls._event_value(
                item, "reserveDate", "createdDate", "registeredAt", "createdAt"
            )
            if not entry_at or not exit_at:
                events = item.get("carInOutDateList")
                if isinstance(events, list):
                    movements: list[tuple[str, str]] = []
                    for event in events:
                        if not isinstance(event, dict):
                            continue
                        raw_status = cls._event_value(
                            event, "statusName", "inOutStatusName", "carInOutStatusName", "status"
                        )
                        occurred_at = cls._event_value(
                            event, "statusDate", "inOutDate", "carInOutDate", "createdAt"
                        )
                        if occurred_at and raw_status:
                            movements.append((occurred_at, raw_status))
                    movements.sort(key=lambda value: re.sub(r"\D", "", value[0]))
                    for occurred_at, raw_status in movements:
                        status_upper = raw_status.upper()
                        if not entry_at and (
                            "입" in raw_status or status_upper in {"IN", "ENTER", "I"}
                        ):
                            entry_at = occurred_at
                        if not exit_at and (
                            "출" in raw_status or status_upper in {"OUT", "EXIT", "O"}
                        ):
                            exit_at = occurred_at

            reservation_id = str(item.get("reservationVisitCarId") or item.get("visitCarId") or "")
            visit_date = str(item.get("visitDate") or "")
            identity = reservation_id or "|".join((plate, visit_date, registered_at))
            status = "출차 완료" if exit_at else "주차 중" if entry_at else "입차 대기"
            version_identity = "|".join((identity, entry_at, exit_at, status))
            activity.append(
                {
                    "id": hashlib.sha256(identity.encode("utf-8")).hexdigest()[:24],
                    "reservationId": reservation_id,
                    "notificationId": hashlib.sha256(
                        version_identity.encode("utf-8")
                    ).hexdigest()[:24],
                    "carNumber": plate,
                    "vehicleType": "registered" if registered else "visitor",
                    "vehicleTypeLabel": "세대차량" if registered else "방문차량",
                    "status": status,
                    "entryAt": entry_at,
                    "exitAt": exit_at,
                    "registeredAt": registered_at,
                    "visitDate": visit_date,
                    "visitorName": str(item.get("visitorName") or ""),
                    "source": "apartmenti",
                    "sourceLabel": "아파트아이",
                }
            )
        activity.sort(
            key=lambda item: re.sub(
                r"\D",
                "",
                str(item.get("entryAt") or item.get("registeredAt") or item.get("visitDate") or ""),
            ),
            reverse=True,
        )
        return activity

    def recent_activity(self, limit: int = 10, months: int = 2) -> dict[str, Any]:
        """Return normalized entry/exit activity across registered and visitor vehicles."""
        limit = max(1, min(100, int(limit)))
        months = max(1, min(6, int(months)))
        registered: list[dict[str, Any]] = []
        try:
            registered = self.registered_vehicles()
        except OSError:
            # Some complexes do not expose household-info. visitType still
            # distinguishes registered vehicles in the parking response.
            registered = []
        registered_plates = {
            re.sub(r"\s", "", str(item.get("carNumber") or item.get("carNoInformation") or ""))
            for item in registered
        }
        month_values = [self._month_offset(offset) for offset in range(months)]
        reservations: list[dict[str, Any]] = []
        for month in month_values:
            reservations.extend(self.history(month)["reservations"])
        activity = self._normalize_activity(reservations, registered_plates)
        return {
            "activity": activity[:limit],
            "total": len(activity),
            "limit": limit,
            "months": month_values,
            "registeredVehicles": registered,
            "sources": ["apartmenti"],
        }

    @staticmethod
    def _minutes(value: Any, fallback: int = 0) -> int:
        try:
            return max(0, int(float(value)))
        except (ValueError, TypeError):
            return max(0, int(fallback))

    def dashboard(self, months: int = 6, allow_cached: bool = True) -> dict[str, Any]:
        """Return reservations, actual visits and the current monthly allowance.

        Apartmenti includes reservation-only rows in the same response as actual
        movements.  Keep those concepts separate for the mobile UI and retain a
        credential-free normalized cache for upstream outages or token churn.
        """
        months = max(1, min(6, int(months)))
        try:
            registered: list[dict[str, Any]] = []
            try:
                registered = self.registered_vehicles()
            except OSError:
                registered = []
            registered_plates = {
                re.sub(r"\s", "", str(item.get("carNumber") or item.get("carNoInformation") or ""))
                for item in registered
            }
            month_values = [self._month_offset(offset) for offset in range(months)]
            reservations: list[dict[str, Any]] = []
            current_policy: dict[str, Any] = {}
            for index, month in enumerate(month_values):
                history = self.history(month)
                reservations.extend(history["reservations"])
                if index == 0:
                    current_policy = dict(history.get("policy") or {})
            normalized = self._normalize_activity(reservations, registered_plates)
            visitors = [item for item in normalized if item.get("vehicleType") == "visitor"]
            today_key = date.today().strftime("%Y%m%d")
            pending_reservations = []
            for item in visitors:
                if item.get("entryAt"):
                    continue
                visit_key = re.sub(r"\D", "", str(item.get("visitDate") or ""))[:8]
                if visit_key and visit_key < today_key:
                    continue
                pending_reservations.append(item)
            actual_visits = [item for item in visitors if item.get("entryAt")]
            total_minutes = self._minutes(
                current_policy.get("freeParkingHour"), self.monthly_allowance_minutes
            )
            remaining_minutes = self._minutes(
                current_policy.get("remainTime"), total_minutes
            )
            if remaining_minutes > total_minutes and total_minutes:
                total_minutes = remaining_minutes
            policy = {
                "totalMinutes": total_minutes,
                "remainingMinutes": remaining_minutes,
                "usedMinutes": max(0, total_minutes - remaining_minutes),
                "parkedMinutes": self._minutes(current_policy.get("parkedTime")),
                "discountMinutes": self._minutes(current_policy.get("discountTime")),
                "policyCode": str(current_policy.get("carReservationPolicy") or ""),
                "source": "apartmenti",
            }
            result = {
                "visitorReservations": pending_reservations,
                "visitorActivity": actual_visits,
                "parkingPolicy": policy,
                "registeredVehicles": registered,
                "months": month_values,
                "cached": False,
                "cachedAt": int(time.time()),
            }
            self._save_dashboard_cache(result)
            return result
        except (PermissionError, OSError, ValueError, TypeError) as error:
            if not allow_cached:
                raise
            cached = self._load_dashboard_cache()
            if not cached:
                raise
            cached["cached"] = True
            cached["cacheError"] = str(error)
            return cached

    @staticmethod
    def _validate_visit_date(value: str) -> str:
        try:
            parsed = datetime.strptime(value, "%Y%m%d").date()
        except ValueError as exc:
            raise ValueError("visitDate must use YYYYMMDD") from exc
        today = date.today()
        if parsed < today or parsed > today + timedelta(days=90):
            raise ValueError("방문일은 오늘부터 90일 이내여야 합니다")
        return value

    def reserve(self, car_number: str, visit_date: str, phone: str) -> dict[str, Any]:
        car_number = re.sub(r"\s", "", car_number.strip())
        phone = re.sub(r"\D", "", phone)
        self._validate_visit_date(visit_date)
        if len(car_number) < 7 or len(car_number) > 12:
            raise ValueError("차량 번호를 확인해 주세요")
        if len(phone) != 11:
            raise ValueError("연락처는 11자리여야 합니다")
        payload = {
            "carNoInformation": car_number,
            "visitDate": visit_date,
            "visitPhoneNo": phone,
        }
        with self.lock:
            result = self._parking_request_locked(
                urllib.parse.urljoin(self.parking_root, "parking/visit/reservation"),
                method="POST",
                data=payload,
            )
        reservation_id = str(
            _find(result, "reservationVisitCarId")
            or _find(result, "visitCarId")
            or ""
        )
        result["carNumber"] = car_number
        result["visitDate"] = visit_date
        result["reservationId"] = reservation_id
        return result

    def cancel(self, reservation_id: str) -> dict[str, Any]:
        reservation_id = reservation_id.strip()
        if not reservation_id or not re.fullmatch(r"[A-Za-z0-9_-]+", reservation_id):
            raise ValueError("invalid reservation id")
        with self.lock:
            return self._parking_request_locked(
                urllib.parse.urljoin(
                    self.parking_root,
                    "parking/visit/reservation/" + urllib.parse.quote(reservation_id, safe=""),
                ),
                method="PUT",
            )
