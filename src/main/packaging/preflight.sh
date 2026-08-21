#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Fails fast when anything the bundle target needs is missing, before any of the slow work starts. The bundle is
# a production artifact, so everything production requires — signing identities and notarization credentials —
# is required here too, locally and in CI alike. Every problem is reported in one pass.
set -euo pipefail

if [ "$(uname)" != "Darwin" ]; then
  echo "The bundle target must run on macOS: the .pkg installers require pkgbuild and codesign" >&2
  exit 1
fi

missing=()

if ! command -v go > /dev/null; then
  missing+=("go — required to build the system tray. Install it from https://go.dev/dl/")
fi

if [ -z "${MACOS_SIGN_IDENTITY:-}" ]; then
  missing+=("MACOS_SIGN_IDENTITY — the [Developer ID Application: ...] identity used to sign the app")
fi

if [ -z "${MACOS_INSTALLER_IDENTITY:-}" ]; then
  missing+=("MACOS_INSTALLER_IDENTITY — the [Developer ID Installer: ...] identity used to sign the .pkg")
fi

if [ -z "${MACOS_NOTARY_PROFILE:-}" ]; then
  missing+=("MACOS_NOTARY_PROFILE — the notarytool keychain profile. Create one with [xcrun notarytool store-credentials]")
fi

if [ "${#missing[@]}" -gt 0 ]; then
  echo "The bundle cannot be built until the following are set up:" >&2
  for item in "${missing[@]}"; do
    echo "  - $item" >&2
  done
  exit 1
fi
