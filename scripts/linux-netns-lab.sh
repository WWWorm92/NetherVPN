#!/usr/bin/env bash
# Privileged, disposable Linux namespace integration test. Run only on a lab host.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
if [[ "${EUID}" -ne 0 ]]; then echo "Run as root inside a disposable VM" >&2; exit 1; fi
for bin in ip ping timeout go; do command -v "$bin" >/dev/null || { echo "Missing $bin" >&2; exit 1; }; done
work="$(mktemp -d)"
ns_s="nvpn-s-$$"
ns_c="nvpn-c-$$"
cleanup() {
  set +e
  [[ -n "${server_pid:-}" ]] && kill "$server_pid" 2>/dev/null
  [[ -n "${client_pid:-}" ]] && kill "$client_pid" 2>/dev/null
  wait "${server_pid:-}" "${client_pid:-}" 2>/dev/null
  ip netns del "$ns_s" 2>/dev/null
  ip netns del "$ns_c" 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT INT TERM
go build -o "$work/server" ./cmd/nether-server
go build -o "$work/client" ./cmd/nether-client
go build -o "$work/keygen" ./cmd/nether-keygen
"$work/keygen" > "$work/server-keys"
"$work/keygen" > "$work/client-keys"
server_priv="$(grep '^PRIVATE=' "$work/server-keys" | cut -d= -f2)"
server_pub="$(grep '^PUBLIC=' "$work/server-keys" | cut -d= -f2)"
client_priv="$(grep '^PRIVATE=' "$work/client-keys" | cut -d= -f2)"
client_pub="$(grep '^PUBLIC=' "$work/client-keys" | cut -d= -f2)"
ip netns add "$ns_s"
ip netns add "$ns_c"
ip link add "veths$$" type veth peer name "vethc$$"
ip link set "veths$$" netns "$ns_s"
ip link set "vethc$$" netns "$ns_c"
ip -n "$ns_s" addr add 192.0.2.1/30 dev "veths$$"
ip -n "$ns_c" addr add 192.0.2.2/30 dev "vethc$$"
ip -n "$ns_s" link set lo up
ip -n "$ns_c" link set lo up
ip -n "$ns_s" link set "veths$$" up
ip -n "$ns_c" link set "vethc$$" up
# TUN is created on demand by each process; wait for its appearance.
ip netns exec "$ns_s" "$work/server" -tun nvpn0 -listen 192.0.2.1:51830 -private-key "$server_priv" -client-key "$client_pub" -client-ip 10.77.0.2 > "$work/server.log" 2>&1 &
server_pid=$!
sleep 0.3
ip netns exec "$ns_c" "$work/client" -tun nvpn0 -peer 192.0.2.1:51830 -private-key "$client_priv" -server-key "$server_pub" -server-ip 10.77.0.1 > "$work/client.log" 2>&1 &
client_pid=$!
for i in $(seq 1 50); do
  if ip -n "$ns_s" link show nvpn0 >/dev/null 2>&1 && ip -n "$ns_c" link show nvpn0 >/dev/null 2>&1; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null || ! kill -0 "$client_pid" 2>/dev/null; then
    cat "$work/server.log" "$work/client.log" >&2; exit 1
  fi
  sleep 0.1
done
ip -n "$ns_s" addr add 10.77.0.1/30 dev nvpn0
ip -n "$ns_c" addr add 10.77.0.2/30 dev nvpn0
ip -n "$ns_s" link set nvpn0 mtu 1280 up
ip -n "$ns_c" link set nvpn0 mtu 1280 up
timeout 8 ip netns exec "$ns_c" ping -c 2 -W 2 10.77.0.1 || {
  echo "VPN ping failed" >&2
  cat "$work/server.log" "$work/client.log" >&2
  exit 1
}
echo "NetherVPN isolated TUN ping succeeded"
