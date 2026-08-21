#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Launcher installed as bin/handler inside the distributed bundles. It uses the bundled jlink runtime, so it
# makes no assumptions about the host's Java.
SCRIPT="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || realpath "${BASH_SOURCE[0]}")"
HANDLER_HOME="$(dirname "$(dirname "$SCRIPT")")"

exec "$HANDLER_HOME/runtime/bin/java" \
  -cp "$HANDLER_HOME/lib/*" \
  dev.theagencyhq.handler.Main "$@"
