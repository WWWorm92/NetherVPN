#!/usr/bin/env bash
# Configure NAT and scoped forwarding for NetherVPN without changing host default routes.
set -euo pipefail
usage() {
  echo "Usage: sudo bash scripts/configure-full-tunnel.sh enable|disable|status WAN_INTERFACE [VPN_CIDR] [TUN_NAME]" >&2
  echo "Example: sudo bash scripts/configure-full-tunnel.sh enable eth0 10.77.0.0/24 nvpn0" >&2
  exit 2
}
[[ $# -ge 2 && $# -le 4 ]] || usage
action="$1"; wan="$2"; cidr="${3:-10.77.0.0/24}"; tun="${4:-nvpn0}"
[[ "$action" =~ ^(enable|disable|status)$ ]] || usage
[[ "$wan" =~ ^[a-zA-Z0-9_.:-]{1,15}$ && "$tun" =~ ^[a-zA-Z0-9_.:-]{1,15}$ ]] || { echo "Unsafe interface name" >&2; exit 2; }
[[ "$wan" != "$tun" ]] || { echo "WAN and TUN must differ" >&2; exit 2; }
python3 - "$cidr" <<'PY'
import sys,ipaddress
try:
    cidr=ipaddress.IPv4Network(sys.argv[1],strict=True)
    assert cidr.prefixlen >= 24 and cidr.prefixlen <= 29
except (ValueError,AssertionError):
    sys.exit("VPN_CIDR must be a network with /24../29 prefix, e.g. 10.77.0.0/24")
PY
[[ "$EUID" == 0 ]] || { echo "Run as root via sudo" >&2; exit 1; }
for command in ip iptables sysctl; do
  command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 1; }
done
ip link show "$wan" >/dev/null
# Scope every rule to the VPN subnet and named interfaces.
nat=(-t nat POSTROUTING -s "$cidr" -o "$wan" -j MASQUERADE)
out=(FORWARD -i "$tun" -o "$wan" -s "$cidr" -j ACCEPT)
back=(FORWARD -i "$wan" -o "$tun" -d "$cidr" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT)

present() { local rule; rule="$1"; shift; iptables "$rule" -C "$@" >/dev/null 2>&1; }
insert_once() {
  local chain="$1"; shift
  if ! iptables -C "$chain" "$@" 2>/dev/null; then iptables -I "$chain" 1 "$@"; fi
}
delete_once() {
  local chain="$1"; shift
  if iptables -C "$chain" "$@" 2>/dev/null; then iptables -D "$chain" "$@"; fi
}

case "$action" in
enable)
  sysctl -w net.ipv4.ip_forward=1
  sysctlfile=/etc/sysctl.d/99-nethervpn-forward.conf
  if [[ -e "$sysctlfile" && "$(cat "$sysctlfile")" != 'net.ipv4.ip_forward = 1' ]]; then
    echo "Refusing to overwrite $sysctlfile" >&2; exit 1
  fi
  printf 'net.ipv4.ip_forward = 1\n' > "$sysctlfile"
  if ! iptables -t nat -C "${nat[@]:2}" 2>/dev/null; then iptables -t nat -A "${nat[@]:2}"; fi
  insert_once "${out[@]}"
  insert_once "${back[@]}"
  echo "IPv4 forwarding/NAT enabled: $cidr via $wan; VPN interface $tun"
  echo "Ensure UDP 51830 is permitted by your INPUT firewall."
  ;;
disable)
  delete_once "${back[@]}"
  delete_once "${out[@]}"
  if iptables -t nat -C "${nat[@]:2}" 2>/dev/null; then iptables -t nat -D "${nat[@]:2}"; fi
  if [[ -f /etc/sysctl.d/99-nethervpn-forward.conf ]] && grep -qx 'net.ipv4.ip_forward = 1' /etc/sysctl.d/99-nethervpn-forward.conf; then
    rm /etc/sysctl.d/99-nethervpn-forward.conf
  fi
  echo "NetherVPN firewall rules removed. IPv4 forwarding left unchanged (other services may depend on it)."
  ;;
status)
  echo "IPv4 forwarding: $(sysctl -n net.ipv4.ip_forward)"
  iptables -t nat -C "${nat[@]:2}" && echo "NAT: enabled" || echo "NAT: absent"
  iptables -C "${out[@]}" && echo "VPN outbound: enabled" || echo "VPN outbound: absent"
  iptables -C "${back[@]}" && echo "VPN return: enabled" || echo "VPN return: absent"
  ;;
esac
