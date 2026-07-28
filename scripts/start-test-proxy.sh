#!/usr/bin/env bash
set -Eeuo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version_file="$root_dir/eng/common/testproxy/target_version.txt"
install_dir="$root_dir/target/test-proxy"
archive="$install_dir/test-proxy-standalone-linux-x64.tar.gz"
proxy="$install_dir/Azure.Sdk.Tools.TestProxy"
log_file="$install_dir/test-proxy.log"

show_log() {
    if [[ -f "$log_file" ]]; then
        printf '%s\n' '--- test-proxy log ---' >&2
        cat "$log_file" >&2
    fi
}

on_error() {
    status=$?
    printf 'Failed to start Azure SDK test-proxy (exit %d).\n' "$status" >&2
    show_log
    exit "$status"
}
trap on_error ERR

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    printf '%s\n' 'This helper supports Linux x64 only.' >&2
    exit 1
fi

version="$(tr -d '\r\n' < "$version_file")"
if [[ ! "$version" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    printf 'Invalid test-proxy version in %s: %q\n' "$version_file" "$version" >&2
    exit 1
fi

url="https://github.com/Azure/azure-sdk-tools/releases/download/Azure.Sdk.Tools.TestProxy_${version}/test-proxy-standalone-linux-x64.tar.gz"
rm -rf "$install_dir"
mkdir -p "$install_dir"
printf 'Downloading Azure SDK test-proxy %s for Linux x64...\n' "$version"
curl --fail --location --silent --show-error --retry 3 --output "$archive" "$url"
tar -xzf "$archive" -C "$install_dir"
chmod +x "$proxy"

printf 'Starting Azure SDK test-proxy %s...\n' "$version"
nohup "$proxy" start --storage-location "$root_dir" >"$log_file" 2>&1 </dev/null &
proxy_pid=$!

for _ in {1..30}; do
    if curl --fail --silent --max-time 2 http://localhost:5000/admin/isalive >/dev/null 2>&1; then
        trap - ERR
        printf 'Azure SDK test-proxy is ready (pid %d; log: %s).\n' "$proxy_pid" "$log_file"
        exit 0
    fi
    if ! kill -0 "$proxy_pid" 2>/dev/null; then
        set +e
        wait "$proxy_pid"
        status=$?
        set -e
        (( status == 0 )) && status=1
        printf 'Azure SDK test-proxy exited before becoming ready (exit %d).\n' "$status" >&2
        show_log
        exit "$status"
    fi
    sleep 1
done

printf '%s\n' 'Azure SDK test-proxy did not become ready within 30 seconds.' >&2
show_log
kill "$proxy_pid" 2>/dev/null || true
exit 1
