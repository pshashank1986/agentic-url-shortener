#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$ROOT/scripts/build.sh"
exec java --add-modules jdk.httpserver -cp "$ROOT/target/classes" io.agenticshortener.AgenticUrlShortener "$@"
