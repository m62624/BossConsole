#!/usr/bin/env bash

set -euo pipefail

bundle_root=${CAGEFORGE_NATIVE_TEST_BUNDLE_ROOT:?native security test bundle is required}

# The ordinary Linux job compiles this exact consumer. The restricted guest
# executes only the bundled smoke so native enforcement is tested without
# repeating Gradle dependency resolution inside QEMU.
java -cp "$bundle_root/classes:$bundle_root/lib/*" \
    ai.rever.boss.process.CageforgeNativeSecurityTestMain
