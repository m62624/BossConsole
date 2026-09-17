#!/usr/bin/env bash

set -euo pipefail

cd "$CAGEFORGE_SOURCE_ROOT"

# Resolve and compile the BOSS test consumer while the guest still has its
# unrestricted preparation network. The actual native assertion runs later in
# the restricted QEMU boot, not in this preparation phase.
# Keep the preparation bounded for the pinned four-gigabyte guest: the project
# defaults are appropriate for developer machines but can otherwise let the
# Gradle and Kotlin daemons exhaust the guest before the native smoke starts.
./gradlew \
    :boss-process-manager:compileNativeSecurityTestKotlin \
    :boss-process-manager:nativeSecurityTestClasses \
    --max-workers=1 \
    --no-daemon \
    --console=plain \
    -Dorg.gradle.jvmargs='-Xmx1024M -Dfile.encoding=UTF-8' \
    -Pkotlin.daemon.jvmargs=-Xmx768M
