#!/usr/bin/env bash
# Runs one autotest scenario in the dev client and checks the result.
# Usage: scripts/autotest.sh [scenario]   env: AUTOTEST_TIMEOUT (seconds, default 900), AUTOTEST_WORLD, AUTOTEST_SEED,
#   AUTOTEST_JVM_ARGS (extra -D options for the client, space separated)
set -uo pipefail
SCENARIO="${1:-smoke}"
TIMEOUT="${AUTOTEST_TIMEOUT:-900}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT/run-autotest"
OUT="$RUN_DIR/autotest/$(date +%Y%m%d-%H%M%S)-$SCENARIO"
mkdir -p "$OUT"
cp "$ROOT/scripts/autotest-options.txt" "$RUN_DIR/options.txt"

( cd "$ROOT" && exec ./gradlew --console=plain runAutotest \
    -Pvoxy.autotest.scenario="$SCENARIO" -Pvoxy.autotest.out="$OUT" \
    -Pvoxy.autotest.world="${AUTOTEST_WORLD:-voxy-autotest}" -Pvoxy.autotest.seed="${AUTOTEST_SEED:-8675309}" \
    ${AUTOTEST_JVM_ARGS:+-Pvoxy.autotest.jvmArgs="$AUTOTEST_JVM_ARGS"} ) > "$OUT/gradle.log" 2>&1 &
GRADLE_PID=$!
# The watchdog must not hold this script's stdout open, otherwise a caller piping the output waits for its sleep
( sleep "$TIMEOUT"; echo "autotest: timeout after ${TIMEOUT}s" >> "$OUT/gradle.log"; kill "$GRADLE_PID" 2>/dev/null; pkill -f 'voxy[.]autotest=true' ) >/dev/null 2>&1 &
WATCHDOG=$!
disown "$WATCHDOG"
wait "$GRADLE_PID"; RC=$?
pkill -P "$WATCHDOG" 2>/dev/null; kill "$WATCHDOG" 2>/dev/null; pkill -f 'voxy[.]autotest=true' 2>/dev/null

cp "$RUN_DIR/logs/latest.log" "$OUT/latest.log" 2>/dev/null
grep -nE 'Exception|Mixin apply failed|FATAL|\[Render thread/ERROR\]' "$OUT/latest.log" > "$OUT/log-issues.txt" 2>/dev/null

STATUS=MISSING
[ -f "$OUT/report.json" ] && STATUS="$(jq -r .status "$OUT/report.json")"
echo "scenario: $SCENARIO  gradle-exit: $RC  status: $STATUS  out: $OUT"
[ -f "$OUT/report.json" ] && jq -r '.failures[]?, (.viewpoints[] | "\(.name): mean \(.frameTimeMeanMs*100|floor/100)ms p99 \(.frameTimeP99Ms*100|floor/100)ms lod \(.lodCoverage // "n/a") base \(.baselineDiffFraction // "n/a")"), "glErrors: \(.glErrors) (before first frame: \(.gl.errorsBeforeFirstWorldFrame))  storage: \(.storageBytesStart) -> \(.storageBytesEnd)  instance: \(.voxyInstancePresent) renderer: \(.voxyRendererPresent)  duration: \(.durationSeconds|floor)s"' "$OUT/report.json"
echo "log issues: $(wc -l < "$OUT/log-issues.txt" | tr -d ' ') (see $OUT/log-issues.txt)"
[ "$STATUS" = "PASS" ]
