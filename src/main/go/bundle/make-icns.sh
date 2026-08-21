#!/bin/sh -e
#
# Regenerates AppIcon.icns from the website's logo-mark.svg. The .icns is committed so the build never needs
# ImageMagick; rerun this only when the logo changes. Requires ImageMagick (magick) and macOS iconutil.
#
# Usage: ./make-icns.sh [path-to-logo-mark.svg]

cd "$(dirname "$0")"
svg="${1:-../../../../../website/static/images/logo-mark.svg}"

rm -rf AppIcon.iconset
mkdir AppIcon.iconset
magick -background none -density 300 "$svg" -resize 1024x1024 AppIcon.iconset/icon_512x512@2x.png
for s in 16 32 128 256 512; do
  magick AppIcon.iconset/icon_512x512@2x.png -resize "${s}x${s}" "AppIcon.iconset/icon_${s}x${s}.png"
  d=$((s * 2))
  magick AppIcon.iconset/icon_512x512@2x.png -resize "${d}x${d}" "AppIcon.iconset/icon_${s}x${s}@2x.png"
done
iconutil -c icns AppIcon.iconset -o AppIcon.icns
rm -rf AppIcon.iconset
echo "Wrote $(pwd)/AppIcon.icns"
