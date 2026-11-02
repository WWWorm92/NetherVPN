#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test -f /etc/nethervpn/server.conf || { echo 'Missing /etc/nethervpn/server.conf' >&2; exit 1; }
# The server profile contains its private X25519 key.
sudo chown root:root /etc/nethervpn/server.conf
sudo chmod 0600 /etc/nethervpn/server.conf
go build -o bin/nether-server ./cmd/nether-server
go build -o bin/nether-admin ./cmd/nether-admin
sudo install -o root -g root -m 0755 bin/nether-server /usr/local/bin/nether-server
sudo install -o root -g root -m 0755 bin/nether-admin /usr/local/bin/nether-admin
sudo install -d -o root -g root -m 0700 /etc/nethervpn/peers /etc/nethervpn/clients
sudo install -o root -g root -m 0755 scripts/configure-full-tunnel.sh /usr/local/sbin/nethervpn-full-tunnel
sudo install -o root -g root -m 0644 packaging/systemd/nethervpn-server.service /etc/systemd/system/nethervpn-server.service
sudo install -o root -g root -m 0644 packaging/systemd/nethervpn-full-tunnel.service /etc/systemd/system/nethervpn-full-tunnel.service
sudo systemctl daemon-reload
echo 'Installed nether-server, nether-admin, nethervpn-full-tunnel.'
echo 'Restart: sudo systemctl restart nethervpn-server'
echo 'Create Android profile: sudo nether-admin create -name phone -endpoint PUBLIC_IPV4:51830'
echo 'Enable IPv4 NAT: sudo nethervpn-full-tunnel enable WAN_INTERFACE 10.77.0.0/24 nvpn0'
