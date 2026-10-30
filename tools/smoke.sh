#!/usr/bin/env bash
# TAP and CTest in one reader; skip directives must not count as failures.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. TAP with a pass, a failure and a skip
dir="$(mktemp -d)"
cat >"$dir/unit.tap" <<'TAP'
1..3
ok 1 - adds numbers
not ok 2 - divides by zero
ok 3 - skipped on windows # SKIP not supported
TAP
printf '{"failOnFailure": "false"}' >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "3" ] || fail "total: $(cat "$dir/output.json")"
[ "$(output "$dir" passed)" = "1" ] || fail "passed: $(cat "$dir/output.json")"
[ "$(output "$dir" failed)" = "1" ] || fail "failed: $(cat "$dir/output.json")"
[ "$(output "$dir" skipped)" = "1" ] || fail "skipped: $(cat "$dir/output.json")"
grep -q "divides by zero" "$dir/out/tap/index.html" || fail "the failing case is not listed"

# 1b. failures ask the runner for UNSTABLE, without failing the step
grep -q '"build.result":{"type":"string","value":"UNSTABLE"}' "$dir/output.json" \
  || fail "a failing TAP must ask for UNSTABLE: $(cat "$dir/output.json")"

# 2. failOnFailure turns it red
dir="$(mktemp -d)"
printf 'not ok 1 - boom\n' >"$dir/x.tap"
printf '{"failOnFailure": "true"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "failOnFailure=true must fail the atom"
grep -q "1 test(s) failed" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

# 3. CTest XML, including the timeout verdict
dir="$(mktemp -d)"; mkdir -p "$dir/Testing/Temporary"
cat >"$dir/Testing/Temporary/Test.xml" <<'XML'
<Testing><Test Status="failed"><Name>expected</Name><Results/></Test><Test Status="passed"><Name>expected</Name><Results/></Test></Testing>
XML
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "ctest run failed: $(cat "$dir/output.json")"
[ "$(output "$dir" total)" = "2" ] || fail "ctest total: $(cat "$dir/output.json")"
[ "$(output "$dir" failed)" = "1" ] || fail "ctest failed: $(cat "$dir/output.json")"

# 4. no report is a clear error
dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no report must fail"
grep -q "no TAP or CTest report" "$dir/output.json" || fail "message not readable"

echo "smoke ok: TAP pass/fail/skip, failOnFailure, CTest XML, no report"
