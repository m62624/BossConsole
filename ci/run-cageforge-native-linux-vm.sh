#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: run-cageforge-native-linux-vm.sh --image IMAGE --test-bundle BUNDLE" >&2
    exit 64
}

image=
test_bundle=
while (($# > 0)); do
    case "$1" in
        --image) (($# >= 2)) || usage; image=$2; shift 2 ;;
        --test-bundle) (($# >= 2)) || usage; test_bundle=$2; shift 2 ;;
        *) usage ;;
    esac
done

[[ -f "$image" ]] || { echo "image is missing: $image" >&2; exit 66; }
[[ -f "$test_bundle" ]] || { echo "native test bundle is missing: $test_bundle" >&2; exit 66; }
for command in genisoimage qemu-img qemu-system-x86_64 ssh ssh-keygen; do
    command -v "$command" >/dev/null || { echo "required command is missing: $command" >&2; exit 69; }
done
[[ -c /dev/kvm ]] || { echo "CAGEFORGE_KVM_UNAVAILABLE: /dev/kvm is not available" >&2; exit 86; }

runner_temp=${RUNNER_TEMP:-/tmp}
work_dir=$(mktemp -d "$runner_temp/boss-cageforge-vm.XXXXXX")
artifacts_dir=${CAGEFORGE_VM_ARTIFACTS:-}
qemu_pid=
ssh_port=$((22000 + RANDOM % 1000))
ssh_key="$work_dir/guest_ed25519"
overlay="$work_dir/guest-overlay.qcow2"
seed_iso="$work_dir/seed.iso"
test_bundle_iso="$work_dir/test-bundle.iso"
serial_log="$work_dir/qemu-serial.log"
stderr_log="$work_dir/qemu.stderr.log"

cleanup() {
    if [[ -n "$qemu_pid" ]] && kill -0 "$qemu_pid" 2>/dev/null; then
        kill "$qemu_pid" 2>/dev/null || true
        wait "$qemu_pid" 2>/dev/null || true
    fi
    if [[ -n "$artifacts_dir" ]]; then
        mkdir -p "$artifacts_dir"
        # Keep diagnostics, but never export the temporary SSH key, seed ISO, test bundle,
        # or guest disk image. The artifact directory is uploaded by CI and must not contain
        # credentials or a copy of the guest filesystem.
        cp "$serial_log" "$artifacts_dir/qemu-serial.log" 2>/dev/null || true
        cp "$stderr_log" "$artifacts_dir/qemu.stderr.log" 2>/dev/null || true
    fi
    rm -rf "$work_dir"
}
trap cleanup EXIT

ssh-keygen -q -t ed25519 -N '' -f "$ssh_key"
ssh_public_key_value=$(<"$ssh_key.pub")
cat >"$work_dir/meta-data" <<EOF
instance-id: boss-cageforge-local
local-hostname: boss-cageforge
EOF

cat >"$work_dir/user-data" <<EOF
#cloud-config
package_update: true
package_upgrade: false
packages:
  - ca-certificates
  - curl
  - bubblewrap
  - openssh-server
  - openjdk-17-jdk-headless
ssh_pwauth: false
disable_root: true
ssh_authorized_keys:
  - $ssh_public_key_value
write_files:
  - path: /etc/boss-cageforge-bootstrap.sh
    permissions: '0755'
    content: |
      #!/usr/bin/env bash
      set -euo pipefail
      sysctl_config=/etc/sysctl.d/99-boss-cageforge.conf
      bootstrap_log=/var/log/boss-cageforge-bootstrap.log
      : >"\$bootstrap_log"
      exec >>"\$bootstrap_log" 2>&1
      export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      echo '[boss] configuring user namespaces'
      printf '%s\n' 'kernel.unprivileged_userns_clone=1' >"\$sysctl_config"
      sysctl -w kernel.unprivileged_userns_clone=1
      for apparmor_sysctl in \
          kernel.apparmor_restrict_unprivileged_userns \
          kernel.apparmor_restrict_unprivileged_unconfined; do
        apparmor_path=/proc/sys/\${apparmor_sysctl//./\/}
        if [[ -w "\$apparmor_path" ]]; then
          sysctl -w "\$apparmor_sysctl=0"
          printf '%s=0\n' "\$apparmor_sysctl" >>"\$sysctl_config"
        fi
      done
      sysctl --system
      probe_bubblewrap_namespace() {
        local namespace=\$1
        local flag=\$2
        shift 2
        echo "[boss] probing \$namespace namespace (\$flag)"
        timeout --kill-after=5s 15s runuser -u ubuntu -- bwrap \
          --die-with-parent --unshare-user "\$flag" "\$@" --ro-bind / / /bin/true
      }
      probe_bubblewrap_namespace user --unshare-user
      probe_bubblewrap_namespace pid --unshare-pid --as-pid-1
      probe_bubblewrap_namespace ipc --unshare-ipc
      probe_bubblewrap_namespace network --unshare-net
      echo '[boss] probing nested user namespace isolation'
      timeout --kill-after=5s 15s runuser -u ubuntu -- bwrap \
        --die-with-parent --unshare-user --disable-userns --ro-bind / / /bin/true
      echo '[boss] probing root capability removal'
      timeout --kill-after=5s 15s bwrap \
        --die-with-parent --unshare-user --unshare-pid --as-pid-1 --cap-drop ALL \
        --ro-bind / / --proc /proc /bin/sh -c \
        'awk '\''/^Cap(Inh|Prm|Eff|Bnd|Amb):/ { found++; if (\$2 != "0000000000000000") bad=1 } END { exit (found == 5 && bad == 0 ? 0 : 1) }'\'' /proc/self/status'
      touch /var/lib/boss-cageforge-bootstrap-complete
runcmd:
  - [cloud-init-per, once, boss-cageforge-bootstrap, bash, /etc/boss-cageforge-bootstrap.sh]
EOF

qemu-img create -q -f qcow2 -F qcow2 -o size=16G -b "$image" "$overlay"
genisoimage -quiet -output "$seed_iso" -volid CIDATA -joliet -rock "$work_dir/user-data" "$work_dir/meta-data"
genisoimage -quiet -output "$test_bundle_iso" -volid BOSS_TEST_BUNDLE -joliet -rock \
    -graft-points "native-test-bundle.tar.gz=$test_bundle"

ssh_guest() {
    ssh -q -i "$ssh_key" -p "$ssh_port" -o BatchMode=yes -o ConnectTimeout=2 \
        -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ubuntu@127.0.0.1 "$@"
}

start_guest() {
    local network_mode=$1
    local network_spec="user,id=net0,hostfwd=tcp:127.0.0.1:$ssh_port-:22"
    [[ "$network_mode" == restricted ]] && network_spec="user,id=net0,restrict=on,hostfwd=tcp:127.0.0.1:$ssh_port-:22"
    : >"$serial_log"
    : >"$stderr_log"
    qemu-system-x86_64 \
        -machine q35,accel=kvm -cpu host -no-reboot -smp 2 -m 4096 \
        -drive "if=virtio,format=qcow2,file=$overlay" \
        -drive "if=ide,media=cdrom,readonly=on,format=raw,file=$seed_iso" \
        -drive "if=ide,media=cdrom,readonly=on,format=raw,file=$test_bundle_iso" \
        -netdev "$network_spec" -device virtio-net-pci,netdev=net0 \
        -display none -serial "file:$serial_log" >/dev/null 2>"$stderr_log" &
    qemu_pid=$!
}

stop_guest() {
    ssh_guest 'sudo poweroff' >/dev/null 2>&1 || true
    # cloud-init may still be flushing package and filesystem state after the
    # bootstrap marker is written. Give systemd enough time to power off cleanly
    # before falling back to killing QEMU, otherwise the next boot can require
    # an avoidable filesystem recovery.
    for _ in {1..120}; do
        if ! kill -0 "$qemu_pid" 2>/dev/null; then
            wait "$qemu_pid" 2>/dev/null || true
            qemu_pid=
            return
        fi
        sleep 1
    done
    kill "$qemu_pid" 2>/dev/null || true
    wait "$qemu_pid" 2>/dev/null || true
    qemu_pid=
}

wait_for_ssh() {
    for _ in {1..120}; do
        ssh_guest true >/dev/null 2>&1 && return
        kill -0 "$qemu_pid" 2>/dev/null || { tail -n 100 "$serial_log" >&2 || true; cat "$stderr_log" >&2 || true; exit 70; }
        sleep 2
    done
    tail -n 100 "$serial_log" >&2 || true
    cat "$stderr_log" >&2 || true
    exit 70
}

wait_for_bootstrap() {
    for _ in {1..180}; do
        if ssh_guest 'systemctl is-failed --quiet cloud-final.service' >/dev/null 2>&1; then
            ssh_guest 'sudo tail -n 160 /var/log/cloud-init-output.log || true' >&2 || true
            exit 70
        fi
        ssh_guest 'sudo test -f /var/lib/boss-cageforge-bootstrap-complete' >/dev/null 2>&1 && return
        sleep 2
    done
    ssh_guest 'sudo tail -n 160 /var/log/cloud-init-output.log || true' >&2 || true
    exit 70
}

echo '[boss] bootstrapping native guest in unrestricted mode'
start_guest unrestricted
wait_for_ssh
wait_for_bootstrap
stop_guest

echo '[boss] running native security smoke in restricted guest'
start_guest restricted
wait_for_ssh
wait_for_bootstrap
set +e
ssh_guest timeout --kill-after=10s 180s bash -s <<'EOF'
set -euo pipefail
bundle_root="$HOME/boss-native-security-test-bundle"
sudo mkdir -p /mnt/boss-test-bundle
sudo mount -L BOSS_TEST_BUNDLE -o ro /mnt/boss-test-bundle
rm -rf "$bundle_root"
mkdir -p "$bundle_root"
tar --extract \
    --file=/mnt/boss-test-bundle/native-test-bundle.tar.gz \
    --directory="$bundle_root" \
    --no-same-owner
export CAGEFORGE_NATIVE_TEST_BUNDLE_ROOT="$bundle_root"
bash "$bundle_root/ci/cageforge-qemu-suite/run.sh"
sudo umount /mnt/boss-test-bundle
EOF
result=$?
set -e
stop_guest
if [[ "$result" -ne 0 ]]; then
    tail -n 160 "$serial_log" >&2 || true
    cat "$stderr_log" >&2 || true
fi
exit "$result"
