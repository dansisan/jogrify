package com.exifremoval;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.png.PngDirectory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

public class ExifRemoval {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: exif-removal <image> [output]");
            System.err.println("  If output is omitted, overwrites the input file (like mogrify).");
            System.exit(1);
        }

        File inputFile = new File(args[0]);
        File outputFile = args.length >= 2 ? new File(args[1]) : inputFile;

        if (!inputFile.exists()) {
            System.err.println("File not found: " + inputFile);
            System.exit(1);
        }

        ImageInfo info = readImageInfo(inputFile);

        if (!info.needsProcessing()) {
            if (!inputFile.equals(outputFile)) {
                Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("Skipped (no metadata to strip): " + inputFile);
            return;
        }

        BufferedImage image = ImageIO.read(inputFile);

        if (image == null) {
            System.err.println("Could not decode image: " + inputFile);
            System.exit(1);
        }

        BufferedImage oriented = applyOrientation(image, info.orientation);

        String formatName = getFormatName(inputFile.getName());
        writeImage(oriented, formatName, outputFile);

        System.out.println("Processed: " + inputFile + " -> " + outputFile
                + " (orientation=" + info.orientation + ", " + oriented.getWidth() + "x" + oriented.getHeight() + ")");
    }

    static class ImageInfo {
        final int orientation;
        final boolean hasGps;

        ImageInfo(int orientation, boolean hasGps) {
            this.orientation = orientation;
            this.hasGps = hasGps;
        }

        boolean needsProcessing() {
            return hasGps;
        }
    }

    static ImageInfo readImageInfo(File file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);

            boolean hasGps = metadata.getFirstDirectoryOfType(GpsDirectory.class) != null;

            int orientation = 1;

            // First, try standard EXIF directory (works for JPEG)
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            } else {
                // For PNG: EXIF may be hex-encoded in a tEXt chunk ("Raw profile type exif")
                for (Directory dir : metadata.getDirectoriesOfType(PngDirectory.class)) {
                    for (Tag tag : dir.getTags()) {
                        String desc = tag.getDescription();
                        if (desc != null && desc.contains("Raw profile type exif")) {
                            int parsed = parseOrientationFromRawExifProfile(desc);
                            if (parsed > 0) { orientation = parsed; break; }
                        }
                    }
                    if (orientation > 1) break;
                }
            }

            return new ImageInfo(orientation, hasGps);
        } catch (Exception e) {
            // No EXIF or unreadable — treat as normal, no GPS
            return new ImageInfo(1, false);
        }
    }

    /**
     * Parse EXIF orientation from a PNG tEXt "Raw profile type exif" chunk.
     * The format is: header lines followed by hex-encoded EXIF bytes.
     */
    static int parseOrientationFromRawExifProfile(String rawProfile) {
        try {
            // The raw profile has a format like:
            //   "Raw profile type exif: \nexif\n    9961\n4578696600004d4d..."
            // We need to extract the hex string, decode it, and parse EXIF from it.
            String[] lines = rawProfile.split("\n");
            StringBuilder hexBuilder = new StringBuilder();
            boolean pastHeader = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!pastHeader) {
                    // Skip "Raw profile type exif:", "exif", and the byte count line
                    if (trimmed.matches("[0-9a-fA-F]{10,}")) {
                        pastHeader = true;
                        hexBuilder.append(trimmed);
                    }
                } else {
                    hexBuilder.append(trimmed);
                }
            }

            String hex = hexBuilder.toString();
            if (hex.isEmpty()) return -1;

            byte[] exifBytes = hexToBytes(hex);

            // Parse EXIF from the raw bytes using metadata-extractor's ExifReader
            Metadata exifMetadata = new Metadata();
            new ExifReader().extract(new com.drew.lang.ByteArrayReader(exifBytes), exifMetadata, ExifReader.JPEG_SEGMENT_PREAMBLE.length());

            ExifIFD0Directory exifDir = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // Failed to parse — fall through
        }
        return -1;
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream(len / 2);
        for (int i = 0; i + 1 < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) continue;
            out.write((hi << 4) | lo);
        }
        return out.toByteArray();
    }

    /**
     * Apply EXIF orientation transform, matching ImageMagick's -auto-orient behavior.
     *
     * Orientation values:
     *   1 = normal
     *   2 = flipped horizontally
     *   3 = rotated 180
     *   4 = flipped vertically
     *   5 = transposed (rotate 90 CW + flip horizontally)
     *   6 = rotated 90 CW
     *   7 = transverse (rotate 90 CCW + flip horizontally)
     *   8 = rotated 90 CCW
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }

        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform t = new AffineTransform();

        boolean swapDimensions = false;

        switch (orientation) {
            case 2: // flip horizontal
                t.scale(-1, 1);
                t.translate(-w, 0);
                break;
            case 3: // rotate 180
                t.translate(w, h);
                t.rotate(Math.PI);
                break;
            case 4: // flip vertical
                t.scale(1, -1);
                t.translate(0, -h);
                break;
            case 5: // transpose: rotate 90 CW then flip horizontal
                t.rotate(Math.PI / 2);
                t.scale(1, -1);
                swapDimensions = true;
                break;
            case 6: // rotate 90 CW
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
                swapDimensions = true;
                break;
            case 7: // transverse: rotate 90 CW then flip vertically
                t.translate(0, w);
                t.scale(1, -1);
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
                swapDimensions = true;
                break;
            case 8: // rotate 90 CCW
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
                swapDimensions = true;
                break;
        }

        int destW = swapDimensions ? h : w;
        int destH = swapDimensions ? w : h;

        BufferedImage dest = new BufferedImage(destW, destH, src.getType() != 0 ? src.getType() : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return dest;
    }

    static String getFormatName(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".bmp")) return "bmp";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "tiff";
        if (lower.endsWith(".webp")) return "webp";
        return "jpeg";
    }

    static void writeImage(BufferedImage image, String format, File output) throws Exception {
        if ("jpeg".equals(format)) {
            writeWithQuality(image, "jpeg", output, 0.85f);
        } else if ("webp".equals(format)) {
            writeWithQuality(image, "webp", output, 0.80f);
        } else if ("png".equals(format)) {
            writeWithQuality(image, "png", output, 0.0f);
        } else {
            ImageIO.write(image, format, output);
        }
    }

    static void writeWithQuality(BufferedImage image, String format, File output, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IllegalStateException("No " + format.toUpperCase() + " writer available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            String[] types = param.getCompressionTypes();
            if (types != null && types.length > 0) {
                param.setCompressionType(types[0]);
            }
            param.setCompressionQuality(quality);
        }

        // Ensure we have a type compatible with JPEG (no alpha channel)
        BufferedImage toWrite = image;
        if ("jpeg".equals(format) && (image.getType() == BufferedImage.TYPE_INT_ARGB || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)) {
            toWrite = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = toWrite.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(toWrite, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
