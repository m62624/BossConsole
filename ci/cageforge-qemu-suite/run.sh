#!/usr/bin/env bash

set -euo pipefail

cd "$CAGEFORGE_SOURCE_ROOT"

# This task is deliberately separate from the ordinary three-OS test matrix.
# The Gradle task itself fails on non-Linux and never silently skips native
# capability failures.
# Keep the restricted guest invocation bounded by the same resource contract as
# preparation; otherwise a later test change could make the native lane fail
# before Cageforge is exercised.
./gradlew \
    :boss-process-manager:nativeSecurityTest \
    --offline \
    --max-workers=1 \
    --no-daemon \
    --console=plain \
    -Dorg.gradle.jvmargs='-Xmx1024M -Dfile.encoding=UTF-8' \
    -Pkotlin.daemon.jvmargs=-Xmx768M
