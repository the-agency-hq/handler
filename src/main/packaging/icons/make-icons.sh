#!/bin/sh -e
#
# Regenerates the Linux icon asset from the website's logo-mark.svg, the same source
# src/main/go/bundle/make-icns.sh uses for the macOS .icns. The output is committed so the build never needs
# ImageMagick; rerun this only when the logo changes.
#
# Usage: ./make-icons.sh [path-to-logo-mark.svg]

cd "$(dirname "$0")"
svg="${1:-../../../../../website/static/images/logo-mark.svg}"

magick -background none -density 300 "$svg" -resize 256x256 handler-256.png
echo "Wrote $(pwd)/handler-256.png"
