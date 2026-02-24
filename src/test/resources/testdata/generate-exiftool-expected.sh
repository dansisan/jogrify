#!/usr/bin/env bash
# Generate exiftool reference outputs for comparison tests.
# Requires: exiftool (https://exiftool.org/)
#
# Run from the testdata/ directory:
#   cd src/test/resources/testdata && bash generate-exiftool-expected.sh
#
# Re-run whenever new test images are added to input/.
# Commit the results in expected-exiftool/.

set -euo pipefail
cd "$(dirname "$0")"

OUTDIR="expected-exiftool"
rm -rf "$OUTDIR"
mkdir -p "$OUTDIR"

count=0

for f in input/*; do
    base=$(basename "$f")

    # Read orientation from CSV (-1 or empty = no orientation tag)
    orientation=$(grep "input/$base," metadata-ground-truth.csv \
        | head -1 \
        | awk -F',' '{gsub(/^ +| +$/, "", $4); print $4}')

    # Read hasExif, hasIptc, hasXmp from CSV
    hasExif=$(grep "input/$base," metadata-ground-truth.csv \
        | head -1 \
        | awk -F',' '{gsub(/^ +| +$/, "", $2); print $2}')
    hasIptc=$(grep "input/$base," metadata-ground-truth.csv \
        | head -1 \
        | awk -F',' '{gsub(/^ +| +$/, "", $5); print $5}')
    hasXmp=$(grep "input/$base," metadata-ground-truth.csv \
        | head -1 \
        | awk -F',' '{gsub(/^ +| +$/, "", $6); print $6}')

    if [ "$hasExif" != "true" ] && [ "$hasIptc" != "true" ] && [ "$hasXmp" != "true" ]; then
        # No metadata — file would not be processed; copy unchanged
        cp "$f" "$OUTDIR/$base"
        echo "  copy (no meta):  $base"
    elif [ "$orientation" = "-1" ] || [ -z "$orientation" ]; then
        # Has EXIF but no orientation tag: strip everything
        exiftool -all= "$f" -o "$OUTDIR/$base" 2>/dev/null
        echo "  strip all:      $base"
    else
        # Has EXIF with orientation tag: strip all, preserve orientation
        exiftool -all= -tagsfromfile @ -IFD0:Orientation# \
            "$f" -o "$OUTDIR/$base" 2>/dev/null
        echo "  strip+orient:   $base"
    fi
    count=$((count + 1))
done

echo ""
echo "Generated $count reference files in $OUTDIR/"
echo "Verify with: exiftool -G1 -s $OUTDIR/*"
