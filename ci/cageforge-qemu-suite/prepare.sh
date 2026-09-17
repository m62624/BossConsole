#!/usr/bin/env bash

set -euo pipefail

cd "$CAGEFORGE_SOURCE_ROOT"

# Resolve and compile the BOSS test consumer while the guest still has its
# unrestricted preparation network. The actual native assertion runs later in
# the restricted QEMU boot, not in this preparation phase.
./gradlew \
    :boss-process-manager:compileNativeSecurityTestKotlin \
    :boss-process-manager:nativeSecurityTestClasses \
    --no-daemon \
    --console=plain
