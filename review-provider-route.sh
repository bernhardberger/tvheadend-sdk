#!/usr/bin/env bash

set -euo pipefail

OPENCHAMBER_ENV_FILE="${OPENCHAMBER_ENV_FILE:-/etc/openchamber/openchamber.env}"
OPENCHAMBER_QUOTA_URL="http://127.0.0.1:3000/api/quota/claude"
OPENCHAMBER_LOGIN_URL="http://127.0.0.1:3000/auth/session"

log_skip() {
  printf 'Opus review skipped: %s\n' "$1" >&2
}

fetch_claude_quota() {
  unset OPENCHAMBER_UI_PASSWORD
  python3 - "$OPENCHAMBER_ENV_FILE" "$OPENCHAMBER_LOGIN_URL" \
    "$OPENCHAMBER_QUOTA_URL" <<'PY'
import http.cookiejar
import json
import os
import shlex
import stat
import sys
import urllib.request

ENV_KEY = "OPENCHAMBER_UI_PASSWORD"
MAX_RESPONSE_BYTES = 1024 * 1024


def parse_password(value_expression):
    expression = value_expression.strip()
    if expression.startswith("'"):
        closing_quote = expression.find("'", 1)
        if closing_quote < 0:
            raise ValueError("credential assignment is invalid")
        suffix = expression[closing_quote + 1 :].strip()
        if suffix and not suffix.startswith("#"):
            raise ValueError("credential assignment syntax is unsupported")
        value = expression[1:closing_quote]
        if not value:
            raise ValueError("credential assignment is invalid")
        return value
    if "$" in expression or "`" in expression:
        raise ValueError("credential assignment syntax is unsupported")
    values = shlex.split(expression, comments=True, posix=True)
    if len(values) != 1 or not values[0]:
        raise ValueError("credential assignment is invalid")
    return values[0]


def read_password(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        os.close(descriptor)
        raise ValueError("credential path is not a regular file")
    if metadata.st_uid != 0 or stat.S_IMODE(metadata.st_mode) != 0o600:
        os.close(descriptor)
        raise ValueError("credential file ownership or mode is invalid")

    password = None
    with os.fdopen(descriptor, encoding="utf-8") as stream:
        for line in stream:
            candidate = line.strip()
            if not candidate or candidate.startswith("#"):
                continue
            if candidate.startswith("export "):
                candidate = candidate[7:].lstrip()
            key, separator, value_expression = candidate.partition("=")
            if separator != "=" or key.strip() != ENV_KEY:
                continue
            if password is not None:
                raise ValueError("credential assignment syntax is unsupported")
            password = parse_password(value_expression)
    if password is None:
        raise ValueError("credential assignment is missing")
    return password


def read_response(response):
    payload = response.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise ValueError("response was too large")
    return payload


try:
    password = read_password(sys.argv[1])
    login_payload = json.dumps(
        {"password": password, "trustDevice": False}, separators=(",", ":")
    ).encode("utf-8")
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
    )
    login_request = urllib.request.Request(
        sys.argv[2],
        data=login_payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with opener.open(login_request, timeout=12) as response:
        read_response(response)
    with opener.open(sys.argv[3], timeout=12) as response:
        quota_payload = read_response(response)
except Exception:
    raise SystemExit(1)

sys.stdout.buffer.write(quota_payload)
PY
}

evaluate_quota() {
  python3 -c '
import json
import math
import sys

FIVE_HOUR_MINIMUM = 15.0
WEEKLY_MINIMUM = 2.0

try:
    payload = json.load(sys.stdin)
except Exception:
    print("quota response was not valid JSON")
    raise SystemExit(2)

if not isinstance(payload, dict) or payload.get("ok") is not True or payload.get("configured") is not True:
    print("Claude quota telemetry was unavailable")
    raise SystemExit(2)

usage = payload.get("usage")
windows = usage.get("windows") if isinstance(usage, dict) else None
if not isinstance(windows, dict):
    print("Claude quota windows were missing")
    raise SystemExit(2)

def remaining(window_name, window):
    if not isinstance(window, dict):
        print(f"Claude {window_name} quota window was missing")
        raise SystemExit(2)
    value = window.get("remainingPercent")
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
        or value > 100
    ):
        print(f"Claude {window_name} remaining quota was invalid")
        raise SystemExit(2)
    return float(value)

five_hour = remaining("5h", windows.get("5h"))
weekly = remaining("7d", windows.get("7d"))
if five_hour <= FIVE_HOUR_MINIMUM:
    print(f"Claude 5h remaining quota is {five_hour:g}% (requires >{FIVE_HOUR_MINIMUM:g}%)")
    raise SystemExit(1)
if weekly <= WEEKLY_MINIMUM:
    print(f"Claude 7d remaining quota is {weekly:g}% (requires >{WEEKLY_MINIMUM:g}%)")
    raise SystemExit(1)

models = usage.get("models")
if not isinstance(models, dict):
    print("Claude model quota windows were invalid")
    raise SystemExit(2)
opus_found = False
for model_name, model_usage in models.items():
    if "opus" not in str(model_name).lower():
        continue
    opus_found = True
    model_windows = model_usage.get("windows") if isinstance(model_usage, dict) else None
    opus_weekly = remaining(f"Opus-scoped 7d ({model_name})", model_windows.get("7d") if isinstance(model_windows, dict) else None)
    if opus_weekly <= WEEKLY_MINIMUM:
        print(f"Opus-scoped 7d remaining quota is {opus_weekly:g}% (requires >{WEEKLY_MINIMUM:g}%)")
        raise SystemExit(1)
if not opus_found:
    print("Opus-scoped 7d quota window was missing")
    raise SystemExit(2)

print("eligible")
' 2>/dev/null
}

select_eligible_route() {
  local payload reason rc
  if ! payload="$(fetch_claude_quota)"; then
    log_skip "trustworthy Claude quota telemetry is unavailable"
    printf 'sol\n'
    return 0
  fi

  set +e
  reason="$(printf '%s' "$payload" | evaluate_quota)"
  rc=$?
  set -e
  if (( rc == 0 )); then
    printf 'opus\n'
  else
    log_skip "${reason:-trustworthy Claude quota telemetry is unavailable}"
    printf 'sol\n'
  fi
}

evaluate_fixture_route() {
  local reason rc
  set +e
  reason="$(evaluate_quota)"
  rc=$?
  set -e
  if (( rc == 0 )); then
    printf 'opus\n'
  else
    log_skip "${reason:-trustworthy Claude quota telemetry is unavailable}"
    printf 'sol\n'
  fi
}

selection="${2:-default}"
case "${1:-select}" in
  select)
    case "$selection" in
      eligible)
        select_eligible_route
        ;;
      default|release|lower-stakes)
        printf 'sol\n'
        ;;
      *)
        printf 'usage: %s select [eligible|release|lower-stakes]\n' "$0" >&2
        exit 2
        ;;
    esac
    ;;
  fallback)
    printf 'sol\n'
    ;;
  status)
    route="sol"
    if [[ "$selection" == "eligible" ]]; then
      route="$(select_eligible_route)"
    elif [[ "$selection" != "default" && "$selection" != "release" && "$selection" != "lower-stakes" ]]; then
      printf 'usage: %s status [eligible|release|lower-stakes]\n' "$0" >&2
      exit 2
    fi
    printf 'route=%s\n' "$route"
    printf 'fallback_route=sol\n'
    printf 'sol_required=true\n'
    printf 'opus_optional=%s\n' "$([[ "$selection" == "eligible" ]] && printf true || printf false)"
    ;;
  evaluate-fixture)
    evaluate_fixture_route
    ;;
  *)
    printf 'usage: %s {select|fallback|status|evaluate-fixture} [eligible|release|lower-stakes]\n' "$0" >&2
    exit 2
    ;;
esac
