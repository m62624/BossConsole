#!/usr/bin/env bash

set -euo pipefail

bundle_root=${CAGEFORGE_NATIVE_TEST_BUNDLE_ROOT:?native security test bundle is required}

# The ordinary Linux job compiles this exact consumer. The restricted guest
# executes only the bundled smoke so native enforcement is tested without
# repeating Gradle dependency resolution inside QEMU.
classpath_entries=("$bundle_root/classes")
while IFS= read -r -d '' jar; do
    classpath_entries+=("$jar")
done < <(find "$bundle_root/lib" -maxdepth 1 -type f -name '*.jar' -print0 | sort -z)
classpath=$(IFS=:; printf '%s' "${classpath_entries[*]}")

# Keep every dependency visible as an actual policy root. A JVM wildcard would
# work for class loading, but it would leave the native policy with one literal
# path ending in `*`, so a protected descendant could not read those JARs.
java -cp "$classpath" \
    ai.rever.boss.process.CageforgeNativeSecurityTestMain
