# exif-removal

A command-line tool that strips EXIF metadata from images and applies orientation corrections, similar to ImageMagick's `mogrify -auto-orient -strip`.

## Supported formats

JPEG, PNG, GIF, TIFF, WebP

## Usage

```bash
# Process an image, writing output to a new file
./gradlew run --args="input.jpg output.jpg"

# Process an image in-place (overwrites the input file)
./gradlew run --args="input.jpg"
```

Or build a distribution and run directly:

```bash
./gradlew installDist
./build/install/exif-removal/bin/exif-removal input.jpg output.jpg
```

## What it does

1. Reads the EXIF orientation tag (supports standard EXIF for JPEG and hex-encoded EXIF in PNG tEXt chunks)
2. Applies the orientation transform (all 8 EXIF orientations)
3. Writes a clean image with no metadata

Output quality: JPEG at 85%, WebP at 80%, PNG at max compression.

## Building

Requires Java 11+.

```bash
./gradlew build
```

## Testing

```bash
./gradlew test
```

28 parameterized tests verify correctness against ImageMagick mogrified reference images:

- **Orientation correction** (18 cases) -- Landscape and Portrait images for all 9 orientation values (0-8)
- **GPS metadata stripping** (5 cases) -- one per format, verifies GPS tags are removed
- **Rotation applied** (5 cases) -- one per format with orientation=8, verifies rotation and metadata stripping

Lossy formats (JPEG, WebP) are compared with an RMSE threshold < 0.02. Lossless formats (PNG, GIF, TIFF) require exact pixel match.

Tests write both `_processed` and `_expected` images side by side in `build/test-output/` (e.g. `gps_processed.jpg` and `gps_expected.jpg`) for easy visual comparison of lossy format differences.

## Dependencies

- [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) -- EXIF parsing
- [webp-imageio](https://github.com/nicoulaj/webp-imageio) -- WebP read/write support via native libwebp
- [JUnit 5](https://junit.org/junit5/) -- testing
