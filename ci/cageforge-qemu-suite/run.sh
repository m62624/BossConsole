#!/usr/bin/env bash

set -euo pipefail

cd "$CAGEFORGE_SOURCE_ROOT"

# This task is deliberately separate from the ordinary three-OS test matrix.
# The Gradle task itself fails on non-Linux and never silently skips native
# capability failures.
./gradlew :boss-process-manager:nativeSecurityTest --offline --no-daemon --console=plain
