#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="$ROOT/src/main/java"
TEST="$ROOT/src/test/java"
OUT="$ROOT/target/classes"
mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d "$OUT" $(find "$MAIN" -name '*.java' -print)
else
  java --module jdk.compiler/com.sun.tools.javac.Main --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d "$OUT" $(find "$MAIN" -name '*.java' -print)
fi
if [[ "${1:-}" == "--tests" ]]; then
  if command -v javac >/dev/null 2>&1; then
    javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp "$OUT" -d "$OUT" $(find "$TEST" -name '*.java' -print)
  else
    java --module jdk.compiler/com.sun.tools.javac.Main --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp "$OUT" -d "$OUT" $(find "$TEST" -name '*.java' -print)
  fi
fi
echo "Compiled classes into $OUT"
