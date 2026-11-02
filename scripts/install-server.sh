#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -f /etc/nethervpn/server.conf || { echo 'Missing /etc/nethervpn/server.conf' >&2; exit 1; }
go build -o bin/nether-server ./cmd/nether-server
sudo install -o root -g root -m 0755 bin/nether-server /usr/local/bin/nether-server
sudo install -o root -g root -m 0644 packaging/systemd/nethervpn-server.service /etc/systemd/system/nethervpn-server.service
sudo systemctl daemon-reload
echo 'Installed; start with sudo systemctl enable --now nethervpn-server'
