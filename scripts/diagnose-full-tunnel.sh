#!/usr/bin/env bash
# Read-only connection checks; does not display profile credentials.
set -uo pipefail
role="${1:-client}"
echo "NetherVPN diagnostics for $role"
show_policy_table() {
  local family="$1"
  local output
  if output="$(ip "$family" route show table 51830 2>&1)"; then
    if [[ -n "$output" ]]; then
      printf '%s\n' "$output"
    else
      echo "IPv$([[ "$family" == "-4" ]] && echo 4 || echo 6) policy table 51830 is empty"
    fi
  elif [[ "$output" == *"FIB table does not exist."* ]]; then
    echo "Routing table 51830 is not initialized yet (normal before Full Tunnel starts)"
  else
    printf '%s\n' "$output"
  fi
}
ip -br -4 address show nvpn0 || true
if [[ "$role" == "client" ]]; then
  systemctl is-active nethervpn-client.service || true
  echo "Existing TUN names and addresses:"
  ip -d -br link show nvpn0 || true
  echo "Check for other /dev/net/tun users if the interface predates this service:"
  ip -4 route get 1.1.1.1 || true
  ip -4 rule show || true
  show_policy_table -4
  ip -6 rule show || true
  show_policy_table -6
  command -v resolvectl >/dev/null && resolvectl status nvpn0 || true
  journalctl -u nethervpn-client.service -n 30 --no-pager || true
elif [[ "$role" == "server" ]]; then
  systemctl is-active nethervpn-server.service || true
  sysctl net.ipv4.ip_forward || true
  echo "Verify NAT with: sudo nethervpn-full-tunnel status eth0 10.77.0.0/24 nvpn0"
  journalctl -u nethervpn-server.service -n 30 --no-pager || true
else
  echo "Usage: diagnose-full-tunnel.sh client|server" >&2
  exit 2
fi
