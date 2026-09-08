#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${1:-forge-1.20.1}
if [ "$#" -gt 0 ]; then shift; fi
JAVA_HOME=${MC_JAVA_HOME_21:-${JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Set MC_JAVA_HOME_21 or JAVA_HOME to JDK 21 or newer." >&2
  exit 2
fi
export JAVA_HOME
# --daemon, not --no-daemon: this bootstrap build runs on every launch, and a
# single-use JVM made it pay a full cold start each time. The explicit flag also
# beats a -Dorg.gradle.daemon=false coming from an inherited GRADLE_OPTS.
# GRADLE_OPTS is sanitized for the real build inside TargetGradleLauncher.
"$ROOT/gradlew" :tools:target-launcher:installDist --daemon
exec "$ROOT/tools/target-launcher/dist/bin/target-launcher" "$TARGET" "$@"
