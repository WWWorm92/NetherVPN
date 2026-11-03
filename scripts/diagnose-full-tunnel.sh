#!/usr/bin/env bash
# Read-only connection checks; does not display profile credentials.
set -uo pipefail
role="${1:-client}"
echo "NetherVPN diagnostics for $role"
ip -br -4 address show nvpn0 || true
if [[ "$role" == "client" ]]; then
  systemctl is-active nethervpn-client.service || true
  ip -4 route get 1.1.1.1 || true
  ip -4 rule show || true
  ip -4 route show table 51830 || true
  ip -6 rule show || true
  ip -6 route show table 51830 || true
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
