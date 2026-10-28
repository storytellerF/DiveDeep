#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT_DIR/scripts/android_e2e.py"
if command -v cygpath >/dev/null 2>&1; then RUNNER="$(cygpath -m "$RUNNER")"; fi
exec "${PYTHON:-python}" "$RUNNER" "$@"
