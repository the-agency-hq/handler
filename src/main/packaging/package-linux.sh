#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Stages the per-user Linux distribution for one architecture and packages it as a .tar.gz. The Handler installs
# into the user's home directory on Linux — no root and no system paths, matching the macOS installer — so the
# archive carries the payload plus the install.sh the user runs after extracting.
#
# Usage: package-linux.sh <version> <goarch>
set -euo pipefail

VERSION="$1"
ARCH="$2"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PACKAGING="$ROOT/src/main/packaging"
STAGE="$ROOT/build/bundle/linux/$ARCH"
DIST="$STAGE/the-agency-hq-handler-$VERSION"
OUT="$ROOT/build/bundle/out"

rm -rf "$STAGE"
mkdir -p "$DIST/handler/bin" \
         "$DIST/handler/lib" \
         "$OUT"

install -m 755 "$ROOT/build/bundle/tray/linux-$ARCH/handler-tray" "$DIST/handler/handler-tray"
cp "$ROOT/build/bundle/lib/"*.jar "$DIST/handler/lib/"
install -m 755 "$PACKAGING/handler-launcher.sh" "$DIST/handler/bin/handler"
cp -R "$ROOT/build/bundle/jre/linux-$ARCH" "$DIST/handler/runtime"
install -m 755 "$PACKAGING/linux/install.sh" "$DIST/install.sh"
cp "$PACKAGING/linux/the-agency-hq-handler.service" "$DIST/"
cp "$PACKAGING/linux/the-agency-hq-handler.desktop" "$DIST/"
if [ -f "$PACKAGING/icons/handler-256.png" ]; then
  cp "$PACKAGING/icons/handler-256.png" "$DIST/the-agency-hq-handler.png"
fi

tar czf "$OUT/the-agency-hq-handler-$VERSION-linux-$ARCH.tar.gz" -C "$STAGE" "the-agency-hq-handler-$VERSION"

echo "Built the Linux [$ARCH] archive into [$OUT]"
