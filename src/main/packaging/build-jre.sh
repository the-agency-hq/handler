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
# The module list is computed with jdeps over the bundled jars, unioned with jdk.unsupported, which jdeps cannot
# infer. A previously linked runtime is reused only when the JDK release and the module list both match.
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

# jdk-25.0.2+10, from the local JDK javaenv installed (Temurin appends -LTS to java.runtime.version)
RUNTIME_VERSION="$(java -XshowSettings:properties -version 2>&1 | awk '/java.runtime.version/ {print $3}')"
RELEASE="jdk-${RUNTIME_VERSION%-LTS}"

# jdeps reports resolution errors on stdout, so checking the exit status and validating the shape of the output
# are both required before the list can be handed to jlink. The lib directory doubles as the module path because
# the requires clauses of modular jars only resolve against the module path, never against the other analyzed
# jars. The feature release for --multi-release comes from the same JDK the jmods are pinned to, so versioned
# entries are resolved at the release that will actually run.
LIB="$ROOT/build/bundle/lib"
FEATURE_RELEASE="${RUNTIME_VERSION%%[.+]*}"
if ! JDEPS_OUT="$(jdeps -q --multi-release "$FEATURE_RELEASE" --print-module-deps --ignore-missing-deps --module-path "$LIB" "$LIB"/*.jar 2>&1)"; then
  echo "jdeps failed to compute the module list for the jars in [$LIB]:" >&2
  echo "$JDEPS_OUT" >&2
  exit 1
fi
COMPUTED="$(echo "$JDEPS_OUT" | tail -1)"
if ! echo "$COMPUTED" | grep -Eq '^[A-Za-z0-9._]+(,[A-Za-z0-9._]+)*$'; then
  echo "jdeps produced [$JDEPS_OUT] instead of a module list" >&2
  exit 1
fi

# jdk.unsupported is the one module jdeps cannot infer: libraries reach sun.misc.Unsafe reflectively, so nothing
# in the jars references it directly. Everything module-info.java requires is derived from the handler jar in
# build/bundle/lib, so this script and module-info.java cannot drift.
MODULES="$(printf 'jdk.unsupported,%s' "$COMPUTED" | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd, -)"

# The tray is the external Go process (src/main/go), so the daemon must stay headless — an AWT-initialized JVM
# inside the user's login session would add its own Dock icon and Cmd-Tab entry next to the Go tray. Nothing
# else keeps AWT out of the runtime, so fail loudly if a dependency drags java.desktop back in.
case ",$MODULES," in
  *",java.desktop,"*)
    echo "The computed module list [$MODULES] contains java.desktop, but the daemon must stay headless because" >&2
    echo "the Go tray owns all UI. Find the dependency that pulled AWT in and remove or exclude it." >&2
    exit 1
    ;;
esac

# Relink whenever the JDK release or the module list changes; a bare directory check would silently reuse and
# ship a stale runtime after either one changed. The stamp is written only after a successful link, so an
# interrupted build relinks too.
STAMP="$OUT.link-key"
LINK_KEY="$RELEASE $MODULES"
if [ -d "$OUT" ] && [ "$(cat "$STAMP" 2>/dev/null || true)" = "$LINK_KEY" ]; then
  echo "Runtime for [$GOOS-$GOARCH] already exists at [$OUT] with the same JDK and modules, skipping"
  exit 0
fi

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

  # Extract to a temp directory and rename on success, so an interrupted extraction never poisons the cache
  rm -rf "$EXTRACT.tmp"
  mkdir "$EXTRACT.tmp"
  tar xzf "$ARCHIVE" -C "$EXTRACT.tmp"
  mv "$EXTRACT.tmp" "$EXTRACT"
fi

JMODS="$(find "$EXTRACT" -name "java.base.jmod" | head -1)"
if [ -z "$JMODS" ]; then
  echo "Unable to locate java.base.jmod inside [$EXTRACT]" >&2
  exit 1
fi
JMODS="$(dirname "$JMODS")"

# Link into a temp directory and rename on success, so an interrupted jlink never leaves a partial runtime the
# skip check above would treat as complete
echo "Linking runtime for [$GOOS-$GOARCH] with modules [$MODULES]"
rm -f "$STAMP"
rm -rf "$OUT" "$OUT.tmp"
mkdir -p "$(dirname "$OUT")"
jlink --module-path "$JMODS" \
      --add-modules "$MODULES" \
      --strip-debug \
      --no-header-files \
      --no-man-pages \
      --compress zip-6 \
      --output "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
echo "$LINK_KEY" > "$STAMP"
