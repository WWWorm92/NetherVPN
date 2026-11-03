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
# Materialize a standard freedesktop icon from the bundled SVG. QtSvgRenderer
# is deliberately used instead of relying on optional SVG image plugins.
ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
mkdir -p "$ICON_DIR" "$HOME/.local/share/applications"
"$ROOT/.venv/bin/python" scripts/render-desktop-icon.py "$ICON_DIR/nethervpn.png"

DESKTOP_FILE="$HOME/.local/share/applications/nethervpn.desktop"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=NetherVPN
GenericName=VPN Client
Comment=NetherVPN Qt desktop client
Exec="$ROOT/.venv/bin/python" "$ROOT/gui/nethervpn_qt.py"
TryExec=$ROOT/.venv/bin/python
Icon=nethervpn
Terminal=false
StartupNotify=true
StartupWMClass=NetherVPN
Categories=Network;Security;
Keywords=VPN;NetherVPN;Network;
EOF
chmod 0644 "$DESKTOP_FILE"

# An older manually created launcher may have a different desktop-file ID.
# Update only *user-owned* NetherVPN entries explicitly pointing to the old
# Tkinter script. This retains application-menu pins/favorites referencing IDs.
for directory in "$HOME/.local/share/applications" "$HOME/Desktop" "$HOME/Рабочий стол"; do
  [[ -d "$directory" ]] || continue
  while IFS= read -r -d '' launcher; do
    [[ "$launcher" == "$DESKTOP_FILE" ]] && continue
    [[ -f "$launcher" && ! -L "$launcher" ]] || continue
    if grep -Eq '^Exec=.*gui/nethervpn\\.py([[:space:]]|$)' "$launcher" &&
       grep -Eq '^(Name|Comment)=.*(NetherVPN|Nether VPN)' "$launcher"; then
      cp "$DESKTOP_FILE" "$launcher"
      chmod 0644 "$launcher"
      echo "Updated legacy NetherVPN launcher: $launcher"
    fi
  done < <(find "$directory" -maxdepth 1 -type f -name '*.desktop' -print0)
done

# Some desktops cache icon metadata; a named SVG is installed as fallback.
SVG_ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
mkdir -p "$SVG_ICON_DIR"
install -m 0644 "$ROOT/assets/nethervpn.svg" "$SVG_ICON_DIR/nethervpn.svg"

# User override for a system-wide legacy desktop ID (no changes to /usr/share).
# A pinned GNOME/KDE favorite may reference that old filename rather than
# nethervpn.desktop. Matching ID overrides make it open the new Qt executable.
for directory in /usr/share/applications /usr/local/share/applications; do
  [[ -d "$directory" ]] || continue
  while IFS= read -r -d '' launcher; do
    [[ -f "$launcher" ]] || continue
    if grep -Eq '^Exec=.*gui/nethervpn\\.py([[:space:]]|$)' "$launcher" &&
       grep -Eq '^(Name|Comment)=.*(NetherVPN|Nether VPN)' "$launcher"; then
      override="$HOME/.local/share/applications/$(basename "$launcher")"
      if [[ "$override" != "$DESKTOP_FILE" && ! -L "$override" ]]; then
        cp "$DESKTOP_FILE" "$override"
        chmod 0644 "$override"
        echo "Overrode legacy system launcher: $(basename "$launcher")"
      fi
    fi
  done < <(find "$directory" -maxdepth 1 -type f -name '*.desktop' -print0)
done

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$HOME/.local/share/applications" || true
fi
if command -v xdg-desktop-menu >/dev/null 2>&1; then
  xdg-desktop-menu forceupdate >/dev/null 2>&1 || true
fi
echo "Installed NetherVPN Qt launcher: $DESKTOP_FILE"
echo "Qt executable: $ROOT/.venv/bin/python $ROOT/gui/nethervpn_qt.py"
echo "Icon: $ICON_DIR/nethervpn.png"
if ! command -v resolvectl >/dev/null; then
  echo 'WARNING: Full Tunnel needs resolvectl (systemd-resolved).' >&2
  echo 'Install and activate systemd-resolved before connecting in Full mode.' >&2
elif ! systemctl is-active --quiet systemd-resolved.service; then
  echo 'WARNING: systemd-resolved is inactive. Full Tunnel DNS will not work.' >&2
fi
