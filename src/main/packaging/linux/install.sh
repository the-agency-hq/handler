#!/bin/sh
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Per-user installer, run from the extracted archive. Everything lands in the current user's home directory — no
# root and no system paths: the payload in ~/.local/lib/the-agency-hq/handler, the CLI on ~/.local/bin, the
# systemd user unit under $XDG_CONFIG_HOME/systemd/user, and the tray autostart under $XDG_CONFIG_HOME/autostart.
# The XDG base variables are honored the way HandlerPaths honors them in the daemon: an absolute value wins,
# anything else falls back to the spec's default. This is a correctness requirement, not politeness — systemctl
# and the desktop session only look under the variables when they are set. The payload and CLI paths stay fixed:
# the spec names ~/.local/bin literally and defines no variable for either.
#
# The service and desktop templates carry a %HOME% placeholder that is stamped here, exactly like the macOS
# postinstall stamps the LaunchAgents — systemd units could take %h, but .desktop Exec lines expand nothing, so
# both are stamped.
#
# Re-running upgrades in place: the running daemon and tray are stopped, the payload is replaced, and both start
# again.
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL="$HOME/.local/lib/the-agency-hq/handler"

CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}"
case "$CONFIG" in /*) ;; *) CONFIG="$HOME/.config" ;; esac
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
case "$DATA" in /*) ;; *) DATA="$HOME/.local/share" ;; esac

if ! command -v systemctl > /dev/null 2>&1; then
  echo "systemd is required: the Handler daemon runs as a systemd user service" >&2
  exit 1
fi

mkdir -p "$HOME/.local/lib/the-agency-hq" \
         "$HOME/.local/bin" \
         "$CONFIG/systemd/user" \
         "$CONFIG/autostart" \
         "$DATA/applications" \
         "$DATA/icons/hicolor/256x256/apps"

# Stop anything running before replacing the payload; on a first install there is nothing to stop
systemctl --user stop the-agency-hq-handler 2> /dev/null || true
pkill -x handler-tray 2> /dev/null || true

rm -rf "$INSTALL"
cp -R "$DIR/handler" "$INSTALL"
ln -sf "$INSTALL/bin/handler" "$HOME/.local/bin/handler"

sed "s|%HOME%|$HOME|g" "$DIR/the-agency-hq-handler.service" \
    > "$CONFIG/systemd/user/the-agency-hq-handler.service"
sed "s|%HOME%|$HOME|g" "$DIR/the-agency-hq-handler.desktop" \
    > "$DATA/applications/the-agency-hq-handler.desktop"
cp "$DATA/applications/the-agency-hq-handler.desktop" \
   "$CONFIG/autostart/the-agency-hq-handler.desktop"
if [ -f "$DIR/the-agency-hq-handler.png" ]; then
  cp "$DIR/the-agency-hq-handler.png" "$DATA/icons/hicolor/256x256/apps/the-agency-hq-handler.png"
fi

systemctl --user daemon-reload
systemctl --user enable --now the-agency-hq-handler

# The autostart entry starts the tray at the next desktop login; when a desktop is already here, start it now
if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
  nohup "$INSTALL/handler-tray" > /dev/null 2>&1 &
fi

case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) echo "Add [$HOME/.local/bin] to your PATH to run [handler] directly." ;;
esac

echo "The Agency Handler is installed."
echo ""
echo "Log in to The Agency HQ with:"
echo "  handler login"
