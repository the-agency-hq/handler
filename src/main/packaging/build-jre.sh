#!/bin/bash
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Builds a trimmed Java runtime for one target platform using jlink. The local JDK 25 cross-links against the
# target platform's jmods, which are downloaded from the same Adoptium API javaenv installs Temurin from and
# cached under build/jdks. Every target downloads — including the host platform — so the build behaves the same
# on every machine regardless of how its JDK was installed. Temurin publishes jmods as a separate ~80MB [jmods]
# image type (the JDK archives themselves stopped bundling jmods in 25, JEP 493), and the download is pinned to
# the exact release of the local JDK because jlink refuses jmods whose java.base version differs from its own.
# The module list is computed with jdeps over the bundled jars, unioned with the modules the Handler is known
# to need.
#
# Usage: build-jre.sh <goos> <goarch> <jdk-os> <jdk-arch>
#   goos/goarch:     Go-style platform names used for build output directories (darwin/linux, arm64/amd64)
#   jdk-os/jdk-arch: Adoptium API platform names (mac/linux, aarch64/x64)
set -euo pipefail

GOOS="$1"
GOARCH="$2"
JDK_OS="$3"
JDK_ARCH="$4"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
JDKS="$ROOT/build/jdks"
OUT="$ROOT/build/bundle/jre/$GOOS-$GOARCH"

if [ -d "$OUT" ]; then
  echo "Runtime for [$GOOS-$GOARCH] already exists at [$OUT], skipping (delete it to rebuild)"
  exit 0
fi

# jdk-25.0.2+10, from the local JDK javaenv installed (Temurin appends -LTS to java.runtime.version)
RUNTIME_VERSION="$(java -XshowSettings:properties -version 2>&1 | awk '/java.runtime.version/ {print $3}')"
RELEASE="jdk-${RUNTIME_VERSION%-LTS}"

NAME="jmods-$(echo "$RELEASE" | tr '+' '_')-$JDK_OS-$JDK_ARCH"
ARCHIVE="$JDKS/$NAME.tar.gz"
EXTRACT="$JDKS/$NAME"

if [ ! -d "$EXTRACT" ]; then
  mkdir -p "$JDKS"
  if [ ! -f "$ARCHIVE" ]; then
    ENCODED="$(echo "$RELEASE" | sed 's/+/%2B/')"
    URL="https://api.adoptium.net/v3/binary/version/$ENCODED/$JDK_OS/$JDK_ARCH/jmods/hotspot/normal/eclipse?project=jdk"
    echo "Downloading [$RELEASE] jmods for [$JDK_OS/$JDK_ARCH] from [$URL]"
    curl -fL -o "$ARCHIVE.part" "$URL"
    mv "$ARCHIVE.part" "$ARCHIVE"
  fi

  mkdir -p "$EXTRACT"
  tar xzf "$ARCHIVE" -C "$EXTRACT"
fi

JMODS="$(find "$EXTRACT" -name "java.base.jmod" | head -1)"
if [ -z "$JMODS" ]; then
  echo "Unable to locate java.base.jmod inside [$EXTRACT]" >&2
  exit 1
fi
JMODS="$(dirname "$JMODS")"

# The modules module-info.java requires, plus jdk.unsupported for libraries that reach for sun.misc.Unsafe
BASE_MODULES="java.base,java.desktop,java.logging,java.net.http,jdk.unsupported"
LIB="$ROOT/build/bundle/lib"
COMPUTED="$(jdeps -q --multi-release 25 --print-module-deps --ignore-missing-deps "$LIB"/*.jar 2>/dev/null | tail -1 || true)"
MODULES="$(printf '%s,%s' "$BASE_MODULES" "$COMPUTED" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -)"

echo "Linking runtime for [$GOOS-$GOARCH] with modules [$MODULES]"
jlink --module-path "$JMODS" \
      --add-modules "$MODULES" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress zip-6 \
      --output "$OUT"
