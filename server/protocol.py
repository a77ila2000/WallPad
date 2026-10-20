"""COMMAX fixed-width RS-485 protocol helpers.

The decoder only assigns meanings that were observed on this installation.
Unknown frames remain available as raw hexadecimal data.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Iterable


FRAME_SIZE = 8
ELEVATOR_CALL_FRAME = bytes.fromhex("A0 01 01 00 08 15 00 BF")


def checksum(data: Iterable[int]) -> int:
    return sum(data) & 0xFF


def is_valid_frame(frame: bytes) -> bool:
    return len(frame) == FRAME_SIZE and checksum(frame[:7]) == frame[7]


def hex_frame(frame: bytes) -> str:
    return " ".join(f"{value:02X}" for value in frame)


def bcd_decode(value: int) -> int | None:
    high, low = value >> 4, value & 0x0F
    if high > 9 or low > 9:
        return None
    return high * 10 + low


def bcd_encode(value: int) -> int:
    if not 0 <= value <= 99:
        raise ValueError("BCD value must be between 0 and 99")
    return ((value // 10) << 4) | (value % 10)


@dataclass(frozen=True)
class DecodedFrame:
    kind: str
    raw: str
    values: dict[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def decode_frame(frame: bytes) -> DecodedFrame:
    if not is_valid_frame(frame):
        raise ValueError("invalid COMMAX frame")

    raw = hex_frame(frame)
    head = frame[0]

    if head in (0x82, 0x84):
        current = bcd_decode(frame[3])
        setpoint = bcd_decode(frame[4])
        if 1 <= frame[2] <= 16 and current is not None and setpoint is not None:
            enabled = {0x80: False, 0x81: True}.get(frame[1])
            return DecodedFrame(
                "thermostat_ack" if head == 0x84 else "thermostat_status",
                raw,
                {
                    "zone": frame[2],
                    "mode": frame[1],
                    "enabled": enabled,
                    "currentTemperature": current,
                    "setpoint": setpoint,
                },
            )

    if head == 0x04 and 1 <= frame[1] <= 16:
        if frame[2] == 0x03:
            setpoint = bcd_decode(frame[3])
            if setpoint is not None:
                return DecodedFrame(
                    "thermostat_setpoint_command",
                    raw,
                    {"zone": frame[1], "setpoint": setpoint},
                )
        if frame[2] == 0x04:
            enabled = {0x00: False, 0x81: True}.get(frame[3])
            return DecodedFrame(
                "thermostat_power_command",
                raw,
                {"zone": frame[1], "mode": frame[3], "enabled": enabled},
            )

    if head in (0xB0, 0xB1) and 1 <= frame[2] <= 32:
        return DecodedFrame(
            "light_ack" if head == 0xB1 else "light_status",
            raw,
            {"channel": frame[2], "enabled": frame[1] == 0x01},
        )

    # Official-app capture on 2026-09-19. This is the dedicated whole-home
    # all-off switch, separate from the five individual light channels.
    if head == 0xA2 and frame[2] == 0x01:
        return DecodedFrame(
            "batch_switch_status",
            raw,
            {"enabled": frame[1] == 0x01, "channel": frame[2]},
        )

    if head == 0x22 and frame[1] == 0x01 and frame[3] == 0x01:
        return DecodedFrame(
            "batch_switch_command",
            raw,
            {"enabled": frame[2] == 0x01, "channel": frame[1]},
        )

    if head == 0xA0 and frame[2] == 0x01:
        return DecodedFrame(
            "batch_switch_status",
            raw,
            {"channel": frame[2], "enabled": frame[1] == 0x01},
        )

    if head == 0xF6:
        return DecodedFrame(
            "ventilation_status",
            raw,
            {"enabled": frame[1] != 0x00, "mode": frame[1]},
        )

    # Observed alongside Ethernet getEvStatus responses on 2026-09-18.
    # Byte 2 is the above-ground floor as a binary value (0x10 == 16F).
    if (
        head == 0x23
        and frame[1] == 0x01
        and 1 <= frame[2] <= 60
        and frame[3:7] == b"\x00\x00\x00\x00"
    ):
        return DecodedFrame(
            "elevator_floor_status",
            raw,
            {"floor": frame[2], "isBasement": False},
        )

    if head == 0x7F:
        values = [bcd_decode(value) for value in frame[1:7]]
        if all(value is not None for value in values):
            year, month, day, hour, minute, second = values
            return DecodedFrame(
                "clock",
                raw,
                {
                    "year": 2000 + year,
                    "month": month,
                    "day": day,
                    "hour": hour,
                    "minute": minute,
                    "second": second,
                },
            )

    return DecodedFrame("unknown", raw, {"header": head})


def build_setpoint_command(zone: int, temperature: int) -> bytes:
    if not 1 <= zone <= 16:
        raise ValueError("zone must be between 1 and 16")
    if not 5 <= temperature <= 40:
        raise ValueError("temperature must be between 5 and 40 C")
    frame = bytearray((0x04, zone, 0x03, bcd_encode(temperature), 0, 0, 0, 0))
    frame[7] = checksum(frame[:7])
    return bytes(frame)


def build_power_command(zone: int, enabled: bool) -> bytes:
    if not 1 <= zone <= 16:
        raise ValueError("zone must be between 1 and 16")
    # Both values were captured on this installation: 0x81=ON, 0x00=OFF.
    mode = 0x81 if enabled else 0x00
    frame = bytearray((0x04, zone, 0x04, mode, 0, 0, 0, 0))
    frame[7] = checksum(frame[:7])
    return bytes(frame)


def build_light_power_command(channel: int, enabled: bool) -> bytes:
    if not 1 <= channel <= 32:
        raise ValueError("light channel must be between 1 and 32")
    frame = bytearray((0x31, channel, 0x01 if enabled else 0x00, 0, 0, 0, 0, 0))
    frame[7] = checksum(frame[:7])
    return bytes(frame)


def build_batch_switch_command(enabled: bool) -> bytes:
    """Build the captured whole-home all-off ON/OFF command."""
    frame = bytearray((0x22, 0x01, 0x01 if enabled else 0x00, 0x01, 0, 0, 0, 0))
    frame[7] = checksum(frame[:7])
    return bytes(frame)


def build_elevator_call_command() -> bytes:
    """Build the verified EXT2 trigger that asks the wallpad to call its home floor."""
    return ELEVATOR_CALL_FRAME


class FrameStream:
    """Recover checksum-valid 8-byte frames from arbitrary TCP chunks."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.discarded_bytes = 0

    def feed(self, data: bytes) -> list[bytes]:
        self._buffer.extend(data)
        frames: list[bytes] = []
        while len(self._buffer) >= FRAME_SIZE:
            candidate = bytes(self._buffer[:FRAME_SIZE])
            if is_valid_frame(candidate):
                frames.append(candidate)
                del self._buffer[:FRAME_SIZE]
            else:
                del self._buffer[0]
                self.discarded_bytes += 1
        return frames
