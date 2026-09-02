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

diagnostic_for() {
  local payload="$1"
  printf '%s' "$payload" | "$SELECTOR" evaluate-fixture 2>&1 >/dev/null
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

assert_text_absent() {
  local pattern="$1" text="$2" label="$3"
  if grep -Eq "$pattern" <<< "$text"; then
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

  for agent in sdk-analyze sdk-research; do
    for permission in edit bash task question memory_list memory_set memory_replace; do
      assert_contains "^  ${permission}: deny$" \
        "$REPOSITORY_DIR/.opencode/agents/${agent}.md" \
        "$agent must retain denied $permission permission"
    done

    local actual_external_directory_permissions=''
    local in_external_directory_permissions=false
    while IFS= read -r line; do
      if [[ "$line" == '  external_directory:' ]]; then
        in_external_directory_permissions=true
        continue
      fi
      if [[ "$in_external_directory_permissions" == true ]]; then
        [[ "$line" == '    '* ]] || break
        actual_external_directory_permissions+="${line}"$'\n'
      fi
    done < "$REPOSITORY_DIR/.opencode/agents/${agent}.md"

    assert_equal $'    "*": deny\n    "/root/.gradle/caches": allow\n    "/root/.gradle/caches/**": allow' \
      "${actual_external_directory_permissions%$'\n'}" \
      "$agent external-directory fallback and exact Gradle cache allowlist"
  done
}

healthy='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":16},"7d":{"remainingPercent":3}}}}'
at_five_hour_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":15},"7d":{"remainingPercent":3}}}}'
at_weekly_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":2}}}}'
unavailable='{"ok":false,"configured":true,"error":"Rate limited. Retrying soon."}'
missing_five_hour='{"ok":true,"configured":true,"usage":{"windows":{"7d":{"remainingPercent":100}}}}'
missing_weekly='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100}}}}'
five_hour_over_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":101},"7d":{"remainingPercent":100}}}}'
weekly_under_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":-1}}}}'
weekly_wrong_type='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":"100"}}}}'
nonstandard_number='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":100}}},"extra":NaN}'

assert_equal opus "$(route_for "$healthy")" 'healthy eligible quota'
assert_equal sol "$(route_for "$at_five_hour_guard")" '5h exact guard'
assert_equal sol "$(route_for "$at_weekly_guard")" '7d exact guard'
assert_equal sol "$(route_for "$unavailable")" 'provider unavailable'
assert_equal sol "$(route_for "$missing_five_hour")" 'missing 5h window'
assert_equal sol "$(route_for "$missing_weekly")" 'missing weekly window'
assert_equal sol "$(route_for "$five_hour_over_range")" '5h percentage above range'
assert_equal sol "$(route_for "$weekly_under_range")" '7d percentage below range'
assert_equal sol "$(route_for "$weekly_wrong_type")" '7d percentage with wrong type'
assert_equal sol "$(route_for 'not-json')" 'malformed telemetry'
assert_equal sol "$(route_for "$nonstandard_number")" 'non-standard JSON number'
assert_equal sol "$(printf '{"ok":tru\0e}' | "$SELECTOR" evaluate-fixture 2>/dev/null)" \
  'malformed telemetry containing NUL'

five_hour_diagnostic="$(diagnostic_for "$at_five_hour_guard")"
weekly_diagnostic="$(diagnostic_for "$at_weekly_guard")"
malformed_diagnostic="$(diagnostic_for 'not-json-sensitive-response')"
unavailable_diagnostic="$(diagnostic_for "$unavailable")"
assert_equal 'Opus review skipped: Claude 5h quota is below the review eligibility minimum' \
  "$five_hour_diagnostic" '5h below-threshold diagnostic remains useful'
assert_equal 'Opus review skipped: Claude 7d quota is below the review eligibility minimum' \
  "$weekly_diagnostic" '7d below-threshold diagnostic remains useful'
assert_text_absent '[0-9]+%|requires|remaining' "$five_hour_diagnostic$weekly_diagnostic" \
  'below-threshold diagnostics must redact percentages and policy thresholds'
assert_equal 'Opus review skipped: quota response was not valid JSON' \
  "$malformed_diagnostic" 'malformed telemetry diagnostic remains useful'
assert_text_absent 'not-json-sensitive-response' "$malformed_diagnostic" \
  'malformed telemetry diagnostic must redact response content'
assert_equal 'Opus review skipped: Claude quota telemetry was unavailable' \
  "$unavailable_diagnostic" 'unavailable telemetry diagnostic remains useful'
assert_text_absent 'Rate limited|Retrying soon' "$unavailable_diagnostic" \
  'unavailable telemetry diagnostic must redact authenticated response content'
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
assert_contains 'urllib\.request\.ProxyHandler\(\{\}\)' "$SELECTOR" \
  'selector must disable inherited proxy routing for credentialed requests'

printf 'PASS: %d review-routing assertions\n' "$TESTS"
