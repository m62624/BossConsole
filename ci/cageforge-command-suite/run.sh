#!/usr/bin/env bash
set -euo pipefail

bundle_root=${CAGEFORGE_NATIVE_TEST_BUNDLE_ROOT:?native test bundle root is required}
classpath_entries=("$bundle_root/classes")
while IFS= read -r -d '' jar; do
    classpath_entries+=("$jar")
done < <(find "$bundle_root/lib" -maxdepth 1 -type f -name '*.jar' -print0 | sort -z)
classpath=$(IFS=:; printf '%s' "${classpath_entries[*]}")
test_home=$(mktemp -d /tmp/boss-command-test-home.XXXXXX)
trap 'rmdir "$test_home" 2>/dev/null || true' EXIT
java -Duser.home="$test_home" -cp "$classpath" ai.rever.boss.sandbox.CommandNativeTestRunner
