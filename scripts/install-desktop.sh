#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! /usr/bin/python3 -c 'import tkinter' >/dev/null 2>&1; then
  echo 'Install python3-tk first: sudo apt install python3-tk' >&2
  exit 1
fi
if ! command -v pkexec >/dev/null 2>&1; then
  echo 'Install pkexec: sudo apt install pkexec polkitd' >&2
  exit 1
fi
cd "$ROOT"
go build -o bin/nether-client ./cmd/nether-client
sudo install -o root -g root -m 0755 bin/nether-client /usr/local/bin/nether-client
sudo install -d -m 0755 /usr/local/libexec
sudo install -m 0755 scripts/nether-desktop-helper.py /usr/local/libexec/nether-desktop-helper
sudo install -d -o root -g root -m 0700 /etc/nethervpn
sudo install -o root -g root -m 0644 packaging/systemd/nethervpn-client.service /etc/systemd/system/nethervpn-client.service
sudo systemctl daemon-reload
mkdir -p "$HOME/.local/share/applications"
printf '[Desktop Entry]\nType=Application\nName=NetherVPN\nComment=Experimental Linux VPN client\nExec=/usr/bin/python3 %s/gui/nethervpn.py\nTerminal=false\nCategories=Network;\n' "$ROOT" > "$HOME/.local/share/applications/nethervpn.desktop"
chmod 0644 "$HOME/.local/share/applications/nethervpn.desktop"
echo 'NetherVPN installed in application menu.'
