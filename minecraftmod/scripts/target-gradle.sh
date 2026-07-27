#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TARGET=${1:-forge-1.20.1}
if [ "$#" -gt 0 ]; then shift; fi
JAVA_HOME=${MC_JAVA_HOME_17:-${JAVA_HOME:-}}
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "Set MC_JAVA_HOME_17 or JAVA_HOME to Java 17 or newer." >&2
  exit 2
fi
"$ROOT/gradlew" :tools:target-launcher:installDist --no-daemon
exec "$ROOT/tools/target-launcher/build/install/target-launcher/bin/target-launcher" "$TARGET" "$@"
