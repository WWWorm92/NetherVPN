#!/usr/bin/env bash
# Opt-in authorization for only the installed NetherVPN desktop helper.
set -euo pipefail

MODE="${1:-}"
if [[ "$MODE" != "enable" && "$MODE" != "disable" ]]; then
  echo "Usage: bash scripts/passwordless-desktop.sh enable|disable" >&2
  exit 2
fi

RULE=/etc/polkit-1/rules.d/49-nethervpn-desktop.rules
CALLER="${SUDO_USER:-$(id -un)}"
if [[ "$CALLER" == "root" ]]; then
  echo "Run as the intended desktop user (not from a root login)." >&2
  exit 1
fi
if [[ "$MODE" == "disable" ]]; then
  sudo rm -f -- "$RULE"
  echo "NetherVPN passwordless authorization disabled."
  exit 0
fi

if [[ ! -f /usr/local/libexec/nether-desktop-helper ]]; then
  echo "Install NetherVPN desktop first: bash scripts/install-desktop.sh" >&2
  exit 1
fi
# Validate username before interpolating into the JavaScript rule.
if [[ ! "$CALLER" =~ ^[a-zA-Z_][a-zA-Z0-9_.-]*[$]?$ ]]; then
  echo "Unexpected account name; refusing to create a rule." >&2
  exit 1
fi

st="$(stat -Lc '%u:%a' /usr/local/libexec/nether-desktop-helper)"
if [[ "${st%%:*}" != 0 ]]; then
  echo "Helper must be root-owned." >&2
  exit 1
fi
# The rule intentionally grants only invocation of one fixed installed helper.
# Never allow shell, systemctl, arbitrary paths or sudo NOPASSWD:ALL.
temp="$(mktemp)"
trap 'rm -f "$temp"' EXIT
cat > "$temp" <<EOF
// NetherVPN desktop only. Revoke: bash scripts/passwordless-desktop.sh disable
polkit.addRule(function(action, subject) {
    if (action.id === "org.freedesktop.policykit.exec" &&
        action.lookup("program") === "/usr/local/libexec/nether-desktop-helper" &&
        subject.user === "$CALLER" &&
        subject.local && subject.active) {
        return polkit.Result.YES;
    }
});
EOF
sudo install -o root -g root -m 0644 "$temp" "$RULE"
echo "NetherVPN desktop operations authorized for $CALLER on active local sessions."
echo "Security: this account can start/stop the NetherVPN client and change its root-owned VPN routing configuration without another password."
