#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Assembles "The Agency Handler.app" for one architecture and wraps it in a signed, notarized .pkg installer.
# The installer targets the current user's home directory only (distribution.xml): the app lands in
# ~/Applications, the postinstall runs unprivileged as the installing user, and nothing touches system paths.
# Signing and notarization are required — the bundle is a production artifact — and are configured through:
#   MACOS_SIGN_IDENTITY       "Developer ID Application: ..." — signs every executable and the bundle
#   MACOS_INSTALLER_IDENTITY  "Developer ID Installer: ..."   — signs the .pkg
#   MACOS_NOTARY_PROFILE      notarytool keychain profile     — notarizes and staples the .pkg
#
# Usage: package-macos.sh <version> <goarch>
set -euo pipefail

if [ "$(uname)" != "Darwin" ]; then
  echo "The macOS distribution can only be built on macOS (pkgbuild and codesign are required)" >&2
  exit 1
fi

: "${MACOS_SIGN_IDENTITY:?MACOS_SIGN_IDENTITY is required (Developer ID Application identity)}"
: "${MACOS_INSTALLER_IDENTITY:?MACOS_INSTALLER_IDENTITY is required (Developer ID Installer identity)}"
: "${MACOS_NOTARY_PROFILE:?MACOS_NOTARY_PROFILE is required (notarytool keychain profile)}"

VERSION="$1"
ARCH="$2"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PACKAGING="$ROOT/src/main/packaging"
STAGE="$ROOT/build/bundle/macos/$ARCH"
APP="$STAGE/root/The Agency Handler.app"
OUT="$ROOT/build/bundle/out"

rm -rf "$STAGE"
mkdir -p "$APP/Contents/MacOS" \
         "$APP/Contents/Resources/daemon/bin" \
         "$APP/Contents/Resources/daemon/lib" \
         "$APP/Contents/Resources/LaunchAgents" \
         "$OUT"

# The tray target's dev bundle owns Info.plist and the icon; only the version is stamped at package time
cp "$ROOT/src/main/go/bundle/Info.plist" "$APP/Contents/Info.plist"
plutil -replace CFBundleShortVersionString -string "$VERSION" "$APP/Contents/Info.plist"
plutil -replace CFBundleVersion -string "$VERSION" "$APP/Contents/Info.plist"
cp "$ROOT/src/main/go/bundle/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
install -m 755 "$ROOT/build/bundle/tray/darwin-$ARCH/handler-tray" "$APP/Contents/MacOS/handler-tray"
cp "$ROOT/build/bundle/lib/"*.jar "$APP/Contents/Resources/daemon/lib/"
install -m 755 "$PACKAGING/handler-launcher.sh" "$APP/Contents/Resources/daemon/bin/handler"
cp -R "$ROOT/build/bundle/jre/darwin-$ARCH" "$APP/Contents/Resources/daemon/runtime"
cp "$PACKAGING/macos/dev.theagencyhq.handler.daemon.plist" \
   "$PACKAGING/macos/dev.theagencyhq.handler.tray.plist" \
   "$APP/Contents/Resources/LaunchAgents/"

ENTITLEMENTS="$PACKAGING/macos/entitlements.plist"
echo "Signing with [$MACOS_SIGN_IDENTITY]"

# Nested binaries first, the bundle last — notarization rejects any other order
find "$APP/Contents/Resources/daemon/runtime" -type f \( -name "*.dylib" -o -perm -111 \) -print0 |
  while IFS= read -r -d '' f; do
    codesign --force --timestamp --options runtime --entitlements "$ENTITLEMENTS" -s "$MACOS_SIGN_IDENTITY" "$f"
  done
codesign --force --timestamp --options runtime --entitlements "$ENTITLEMENTS" -s "$MACOS_SIGN_IDENTITY" \
         "$APP/Contents/MacOS/handler-tray"
codesign --force --timestamp --options runtime --entitlements "$ENTITLEMENTS" -s "$MACOS_SIGN_IDENTITY" "$APP"

# BundleIsRelocatable=false keeps the installer from "updating" a stray copy of the app somewhere else instead
# of installing into the home directory it was pointed at
pkgbuild --analyze --root "$STAGE/root" "$STAGE/component.plist"
plutil -replace "0.BundleIsRelocatable" -bool false "$STAGE/component.plist"
pkgbuild --root "$STAGE/root" \
         --install-location /Applications \
         --component-plist "$STAGE/component.plist" \
         --scripts "$PACKAGING/macos/scripts" \
         --identifier dev.theagencyhq.handler \
         --version "$VERSION" \
         "$STAGE/handler-component.pkg"

PKG="$OUT/the-agency-hq-handler-$VERSION-macos-$ARCH.pkg"
productbuild --distribution "$PACKAGING/macos/distribution.xml" \
             --package-path "$STAGE" \
             --sign "$MACOS_INSTALLER_IDENTITY" \
             "$PKG"

echo "Notarizing [$PKG]"
xcrun notarytool submit "$PKG" --keychain-profile "$MACOS_NOTARY_PROFILE" --wait
xcrun stapler staple "$PKG"

echo "Built [$PKG]"
