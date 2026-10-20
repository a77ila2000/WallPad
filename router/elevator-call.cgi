#!/bin/sh

# Authenticated one-shot COMMAX home-floor call. The always-on status poller
# owns the wallpad address and response-capture nft rule. This CGI uses a
# separate source-port range and never retries an ambiguous request.

set -u

[ ! -r /etc/elevator.env ] || . /etc/elevator.env

SERVER_IP="${ELEVATOR_SERVER_IP:?set ELEVATOR_SERVER_IP}"
SERVER_PORT="${ELEVATOR_SERVER_PORT:-29715}"
WALLPAD_IP="${ELEVATOR_WALLPAD_IP:?set ELEVATOR_WALLPAD_IP}"
PORT_FIRST="${ELEVATOR_CALL_PORT_FIRST:-22000}"
PORT_LAST="${ELEVATOR_CALL_PORT_LAST:-22099}"
REQUEST="${ELEVATOR_CALL_REQUEST:-/usr/share/elevator/setEvStatus-request.bin}"
TOKEN_FILE="${ELEVATOR_CALL_TOKEN_FILE:-/etc/elevator-call.token}"
STATE_DIR="${ELEVATOR_CALL_STATE:-/tmp/elevator-call}"
PUBLIC_RESULT="${ELEVATOR_CALL_RESULT:-/www/elevator/call-command.json}"
LOCK_DIR="$STATE_DIR/lock"

json_response() {
    status="$1"
    body="$2"
    printf 'Status: %s\r\n' "$status"
    printf 'Content-Type: application/json; charset=utf-8\r\n'
    printf 'Cache-Control: no-store\r\n'
    printf 'Content-Length: %s\r\n\r\n' "${#body}"
    printf '%s' "$body"
    exit 0
}

[ "${REQUEST_METHOD:-}" = POST ] || json_response '405 Method Not Allowed' '{"accepted":false,"error":"POST required"}'
[ -s "$TOKEN_FILE" ] || json_response '503 Service Unavailable' '{"accepted":false,"error":"control token unavailable"}'
length="${CONTENT_LENGTH:-0}"
case "$length" in ''|*[!0-9]*) length=0 ;; esac
body=''
if [ "$length" -gt 0 ] && [ "$length" -le 4096 ]; then
    body="$(dd bs=1 count="$length" 2>/dev/null)"
fi
expected="$(cat "$TOKEN_FILE")"
supplied="${HTTP_X_HOMEPAD_CONTROL:-}"
if [ -z "$supplied" ]; then
    supplied="$(printf '%s' "$body" | sed -n 's/.*"controlToken"[[:space:]]*:[[:space:]]*"\([a-fA-F0-9]*\)".*/\1/p')"
fi
[ -n "$expected" ] && [ "$supplied" = "$expected" ] || json_response '401 Unauthorized' '{"accepted":false,"error":"unauthorized"}'
[ -s "$REQUEST" ] || json_response '503 Service Unavailable' '{"accepted":false,"error":"call request unavailable"}'

mkdir -p "$STATE_DIR"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    json_response '409 Conflict' '{"accepted":false,"error":"call already in progress"}'
fi
trap 'rm -rf "$LOCK_DIR"' EXIT INT TERM

request_id="$(printf '%s' "$body" | sed -n 's/.*"requestId"[[:space:]]*:[[:space:]]*"\([A-Za-z0-9_-]*\)".*/\1/p')"
case "$request_id" in ''|*[!A-Za-z0-9_-]*) request_id="call-$(date +%s)" ;; esac

if ! ip -4 -o addr show dev br-homenet | grep -q " $WALLPAD_IP/"; then
    json_response '503 Service Unavailable' '{"accepted":false,"error":"status poller is not ready"}'
fi
if ! nft list table bridge elevator_status_poller >/dev/null 2>&1; then
    json_response '503 Service Unavailable' '{"accepted":false,"error":"response capture is not ready"}'
fi

port="$PORT_FIRST"
if [ -r "$STATE_DIR/last-port.txt" ]; then
    last="$(cat "$STATE_DIR/last-port.txt")"
    case "$last" in
        ''|*[!0-9]*) ;;
        *) port=$((last + 1)); [ "$port" -le "$PORT_LAST" ] || port="$PORT_FIRST" ;;
    esac
fi
printf '%s\n' "$port" >"$STATE_DIR/last-port.txt"

response="$STATE_DIR/response-$request_id.tmp"
log="$STATE_DIR/socat-$request_id.log"
socat -T 5 -t 5 STDIO \
    TCP4:"$SERVER_IP":"$SERVER_PORT",bind="$WALLPAD_IP",sourceport="$port",connect-timeout=2 \
    <"$REQUEST" >"$response" 2>"$log"
rc=$?
now="$(date +%s)"
iso="$(date -Iseconds)"

if [ "$rc" -eq 0 ] && grep -q '^HTTP/1.1 200 OK' "$response" && grep -q '<ns:setEvStatusResponse>' "$response" && grep -q '<out>0</out>' "$response"; then
    result="{\"accepted\":true,\"serverAccepted\":true,\"requestId\":\"$request_id\",\"updatedEpoch\":$now,\"updated\":\"$iso\",\"source\":\"ethernet-command\"}"
    printf '%s\n' "$result" >"$PUBLIC_RESULT.tmp"
    mv "$PUBLIC_RESULT.tmp" "$PUBLIC_RESULT"
    mv "$response" "$STATE_DIR/last-response.bin"
    rm -f "$log"
    json_response '200 OK' "$result"
fi

bytes=0
[ -f "$response" ] && bytes="$(wc -c <"$response" | tr -d ' ')"
result="{\"accepted\":false,\"serverAccepted\":false,\"requestId\":\"$request_id\",\"updatedEpoch\":$now,\"source\":\"ethernet-command\",\"error\":\"result unknown; no retry\",\"responseBytes\":$bytes}"
printf '%s\n' "$result" >"$PUBLIC_RESULT.tmp"
mv "$PUBLIC_RESULT.tmp" "$PUBLIC_RESULT"
rm -f "$response"
json_response '502 Bad Gateway' "$result"
