#!/usr/bin/env bash

set -euo pipefail

REPOSITORY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECTOR="$REPOSITORY_DIR/review-provider-route.sh"
TESTS=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_equal() {
  local expected="$1" actual="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: expected '$expected', got '$actual'"
  TESTS=$((TESTS + 1))
}

route_for() {
  local payload="$1"
  printf '%s' "$payload" | "$SELECTOR" evaluate-fixture 2>/dev/null
}

assert_contains() {
  local pattern="$1" file="$2" label="$3"
  grep -Eq "$pattern" "$file" || fail "$label"
  TESTS=$((TESTS + 1))
}

assert_absent() {
  local pattern="$1" file="$2" label="$3"
  if grep -Eq "$pattern" "$file"; then
    fail "$label"
  fi
  TESTS=$((TESTS + 1))
}

assert_agent_permissions() {
  local agent permission
  for agent in sdk-locator sdk-planner sdk-analyze sdk-research sdk-review-sol sdk-review-opus; do
    for permission in question memory_list memory_set memory_replace; do
      assert_contains "^  ${permission}: deny$" \
        "$REPOSITORY_DIR/.opencode/agents/${agent}.md" \
        "$agent must explicitly deny $permission"
    done
  done
}

healthy='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":16},"7d":{"remainingPercent":3}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":3}}}}}}'
at_five_hour_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":15},"7d":{"remainingPercent":3}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":3}}}}}}'
at_weekly_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":2}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":3}}}}}}'
at_opus_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":3}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":2}}}}}}'
unavailable='{"ok":false,"configured":true,"error":"Rate limited. Retrying soon."}'
missing_weekly='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100}}}}'
missing_opus='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":100}},"models":{}}}'
missing_opus_weekly='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":100}},"models":{"Claude Opus 5":{"windows":{}}}}}'
five_hour_over_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":101},"7d":{"remainingPercent":100}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":100}}}}}}'
opus_under_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":100}},"models":{"Claude Opus 5":{"windows":{"7d":{"remainingPercent":-1}}}}}}'

assert_equal opus "$(route_for "$healthy")" 'healthy eligible quota'
assert_equal sol "$(route_for "$at_five_hour_guard")" '5h exact guard'
assert_equal sol "$(route_for "$at_weekly_guard")" '7d exact guard'
assert_equal sol "$(route_for "$at_opus_guard")" 'Opus-scoped exact guard'
assert_equal sol "$(route_for "$unavailable")" 'provider unavailable'
assert_equal sol "$(route_for "$missing_weekly")" 'missing weekly window'
assert_equal sol "$(route_for "$missing_opus")" 'missing Opus model telemetry'
assert_equal sol "$(route_for "$missing_opus_weekly")" 'missing Opus weekly window'
assert_equal sol "$(route_for "$five_hour_over_range")" '5h percentage above range'
assert_equal sol "$(route_for "$opus_under_range")" 'Opus percentage below range'
assert_equal sol "$(route_for 'not-json')" 'malformed telemetry'
assert_equal sol "$(OPENCHAMBER_ENV_FILE=/tmp/tvheadend-sdk-missing-review-route.env \
  REVIEW_ROUTE_TESTING=1 REVIEW_ROUTE_TEST_QUOTA_JSON="$healthy" \
  "$SELECTOR" select eligible 2>/dev/null)" 'legacy fixture environment cannot bypass production fetch'
assert_equal sol "$("$SELECTOR" select default)" 'default remains Sol'
assert_equal sol "$("$SELECTOR" select release)" 'release remains Sol-only'
assert_equal sol "$("$SELECTOR" select lower-stakes)" 'lower-stakes remains Sol-only'
assert_equal sol "$("$SELECTOR" fallback)" 'fallback remains Sol'
assert_equal $'route=sol\nfallback_route=sol\nsol_required=true\nopus_optional=false' \
  "$("$SELECTOR" status release)" 'release status documents fixed policy'

assert_agent_permissions
assert_absent '^[[:space:]]*(source|\.)[[:space:]]' "$SELECTOR" \
  'selector must not source credential files'
assert_absent 'set[[:space:]]+-a|mktemp|FileCookieJar|MozillaCookieJar|LWPCookieJar|curl[[:space:]]' "$SELECTOR" \
  'selector must not export sourced values or persist cookies'
assert_absent 'REVIEW_ROUTE_TESTING|REVIEW_ROUTE_TEST_QUOTA_JSON' "$SELECTOR" \
  'production selector must not contain an environment-controlled fixture bypass'
assert_contains '^  unset OPENCHAMBER_UI_PASSWORD$' "$SELECTOR" \
  'selector must remove inherited credential values before launching Python'
assert_contains 'if "\$" in expression or "`" in expression:' "$SELECTOR" \
  'selector must reject dynamic credential assignment syntax'
assert_contains 'http\.cookiejar\.CookieJar\(\)' "$SELECTOR" \
  'selector must use an in-memory cookie jar'
assert_contains 'urllib\.request\.build_opener' "$SELECTOR" \
  'selector login and quota requests must remain in one Python process'

printf 'PASS: %d review-routing assertions\n' "$TESTS"
