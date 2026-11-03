#!/usr/bin/env bash
# Register the *Qt* NetherVPN desktop application without touching the VPN service.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="$DATA_HOME/applications"
ICON_BASE="$DATA_HOME/icons/hicolor"
PYTHON="$ROOT/.venv/bin/python"
APP="$ROOT/gui/nethervpn_qt.py"
DESKTOP="$APP_DIR/nethervpn.desktop"
ICON="$ICON_BASE/256x256/apps/nethervpn.png"

if [[ ! -x "$PYTHON" || ! -f "$APP" ]]; then
  echo "Qt app missing. Run: bash scripts/install-desktop.sh" >&2
  exit 1
fi
mkdir -p "$APP_DIR" "$(dirname "$ICON")" "$ICON_BASE/scalable/apps"
if ! QT_QPA_PLATFORM=offscreen "$PYTHON" "$ROOT/scripts/render-desktop-icon.py" "$ICON"; then
  echo "PNG icon render failed; using SVG fallback." >&2
fi
install -m 0644 "$ROOT/assets/nethervpn.svg" "$ICON_BASE/scalable/apps/nethervpn.svg"
ICON_ENTRY="$ICON"
[[ -s "$ICON" ]] || ICON_ENTRY="$ICON_BASE/scalable/apps/nethervpn.svg"

cat > "$DESKTOP" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=NetherVPN
GenericName=VPN Client
Comment=NetherVPN Qt VPN client
Exec="$PYTHON" "$APP"
TryExec=$PYTHON
Icon=$ICON_ENTRY
Terminal=false
StartupNotify=true
StartupWMClass=NetherVPN
Categories=Network;Utility;
Keywords=VPN;Tunnel;Network;NetherVPN;
NoDisplay=false
Hidden=false
EOF
chmod 0644 "$DESKTOP"

# Update any user-owned old NetherVPN launcher without destroying unrelated apps.
for directory in "$APP_DIR" "$HOME/Desktop" "$HOME/Рабочий стол"; do
  [[ -d "$directory" ]] || continue
  while IFS= read -r -d '' old; do
    [[ "$old" == "$DESKTOP" || -L "$old" ]] && continue
    if grep -Eq '^Name=NetherVPN([[:space:]]|$)' "$old" &&
       grep -Fq '/gui/nethervpn.py' "$old"; then
      cp "$DESKTOP" "$old"
      echo "Updated previous launcher: $old"
    fi
  done < <(find "$directory" -maxdepth 1 -type f -name '*.desktop' -print0)
done

# A pinned system launcher can be overridden by installing a same-ID user entry.
for directory in /usr/share/applications /usr/local/share/applications; do
  [[ -d "$directory" ]] || continue
  while IFS= read -r -d '' old; do
    if grep -Eq '^Name=NetherVPN([[:space:]]|$)' "$old" &&
       grep -Fq '/gui/nethervpn.py' "$old"; then
      override="$APP_DIR/$(basename "$old")"
      [[ "$override" == "$DESKTOP" || -L "$override" ]] && continue
      cp "$DESKTOP" "$override"
      echo "Overrode system launcher ID: $(basename "$old")"
    fi
  done < <(find "$directory" -maxdepth 1 -type f -name '*.desktop' -print0)
done

if command -v desktop-file-validate >/dev/null 2>&1; then
  desktop-file-validate "$DESKTOP"
fi
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APP_DIR" || true
fi
if command -v xdg-desktop-menu >/dev/null 2>&1; then
  xdg-desktop-menu forceupdate >/dev/null 2>&1 || true
fi
for refresh in kbuildsycoca6 kbuildsycoca5 kbuildsycoca; do
  if command -v "$refresh" >/dev/null 2>&1; then
    "$refresh" --noincremental >/dev/null 2>&1 || true
    break
  fi
done
echo "Registered Qt app: $DESKTOP"
echo "Qt executable: $PYTHON"
echo "Qt entrypoint: $APP"
echo "Icon: $ICON_ENTRY"
if command -v gtk-launch >/dev/null 2>&1; then
  echo "Try: gtk-launch nethervpn"
fi
