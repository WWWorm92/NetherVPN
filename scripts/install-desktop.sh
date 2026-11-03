#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! python3 -m venv --help >/dev/null 2>&1; then
  echo 'Install python3-venv first: sudo apt install python3-venv' >&2
  exit 1
fi
if ! command -v pkexec >/dev/null 2>&1; then
  echo 'Install pkexec: sudo apt install pkexec polkitd' >&2
  exit 1
fi
cd "$ROOT"
if [[ ! -x "$ROOT/.venv/bin/python" ]]; then
  python3 -m venv "$ROOT/.venv"
fi
"$ROOT/.venv/bin/python" -m pip install --disable-pip-version-check "PySide6>=6.6,<7"
"$ROOT/.venv/bin/python" -c "from PySide6.QtWidgets import QApplication"
go build -o bin/nether-client ./cmd/nether-client
sudo install -o root -g root -m 0755 bin/nether-client /usr/local/bin/nether-client
sudo install -d -m 0755 /usr/local/libexec
sudo install -m 0755 scripts/nether-desktop-helper.py /usr/local/libexec/nether-desktop-helper
sudo install -d -o root -g root -m 0700 /etc/nethervpn
sudo install -o root -g root -m 0644 packaging/systemd/nethervpn-client.service /etc/systemd/system/nethervpn-client.service
sudo systemctl daemon-reload
mkdir -p "$HOME/.local/share/applications"
printf '[Desktop Entry]\nType=Application\nName=NetherVPN\nComment=Experimental Linux VPN client\nExec=%s/.venv/bin/python %s/gui/nethervpn_qt.py\nIcon=%s/assets/nethervpn.svg\nTerminal=false\nCategories=Network;\n' "$ROOT" "$ROOT" "$ROOT" > "$HOME/.local/share/applications/nethervpn.desktop"
chmod 0644 "$HOME/.local/share/applications/nethervpn.desktop"
echo 'NetherVPN Qt desktop installed in application menu.'
if ! command -v resolvectl >/dev/null; then
  echo 'WARNING: Full Tunnel needs resolvectl (systemd-resolved).' >&2
  echo 'Install and activate systemd-resolved before connecting in Full mode.' >&2
elif ! systemctl is-active --quiet systemd-resolved.service; then
  echo 'WARNING: systemd-resolved is inactive. Full Tunnel DNS will not work.' >&2
fi
