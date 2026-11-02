#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! /usr/bin/python3 -c 'import tkinter' >/dev/null 2>&1; then
  echo 'Install python3-tk first: sudo apt install python3-tk' >&2
  exit 1
fi
if ! command -v pkexec >/dev/null 2>&1; then
  echo 'Install polkit first: sudo apt install policykit-1' >&2
  exit 1
fi
cd "$ROOT"
go build -o bin/nether-client ./cmd/nether-client
mkdir -p "$HOME/.local/share/applications"
printf '[Desktop Entry]\nType=Application\nName=NetherVPN\nComment=Experimental Linux VPN client\nExec=/usr/bin/python3 %s/gui/nethervpn.py\nTerminal=false\nCategories=Network;\n' "$ROOT" > "$HOME/.local/share/applications/nethervpn.desktop"
chmod 0644 "$HOME/.local/share/applications/nethervpn.desktop"
echo 'NetherVPN installed in application menu.'
