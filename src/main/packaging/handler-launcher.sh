#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Launcher installed as bin/handler inside the distributed bundles. It uses the bundled jlink runtime, so it
# makes no assumptions about the host's Java.
# AWT is forced headless: the Go system tray owns the icon and notifications in deployed installs, and running
# the daemon's AWT tray as well would show two icons and raise every notification twice.
SCRIPT="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || realpath "${BASH_SOURCE[0]}")"
HANDLER_HOME="$(dirname "$(dirname "$SCRIPT")")"

exec "$HANDLER_HOME/runtime/bin/java" \
  -Djava.awt.headless=true \
  -Dhandler.home="$HANDLER_HOME" \
  -cp "$HANDLER_HOME/lib/*" \
  dev.theagencyhq.handler.Main "$@"
