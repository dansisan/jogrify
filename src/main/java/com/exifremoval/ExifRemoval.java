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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.logging.Logger;

public class ExifRemoval {

    private static final Logger LOG = Logger.getLogger(ExifRemoval.class.getName());

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

        process(inputFile, outputFile);
    }

    static void process(File inputFile, File outputFile) throws Exception {
        ImageInfo info = readImageInfo(inputFile);

        if (!info.needsProcessing()) {
            if (!inputFile.equals(outputFile)) {
                Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        String formatName = getFormatName(inputFile.getName());

        if ("jpeg".equals(formatName)) {
            stripJpegGps(inputFile, outputFile, info.orientation);
            return;
        }

        BufferedImage image = ImageIO.read(inputFile);

        if (image == null) {
            throw new IllegalArgumentException("Could not decode image: " + inputFile);
        }

        BufferedImage oriented = applyOrientation(image, info.orientation);
        writeImage(oriented, formatName, outputFile);
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
                            ImageInfo pngInfo = parseImageInfoFromRawExifProfile(desc);
                            if (pngInfo != null) return pngInfo;
                        }
                    }
                }
            }

            return new ImageInfo(orientation, hasGps);
        } catch (Exception e) {
            // No EXIF or unreadable — treat as normal, no GPS
            return new ImageInfo(1, false);
        }
    }

    /**
     * Parse EXIF data from a PNG tEXt "Raw profile type exif" chunk.
     * The format is: header lines followed by hex-encoded EXIF bytes.
     */
    static ImageInfo parseImageInfoFromRawExifProfile(String rawProfile) {
        try {
            String[] lines = rawProfile.split("\n");
            StringBuilder hexBuilder = new StringBuilder();
            boolean pastHeader = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (!pastHeader) {
                    if (trimmed.matches("[0-9a-fA-F]{10,}")) {
                        pastHeader = true;
                        hexBuilder.append(trimmed);
                    }
                } else {
                    hexBuilder.append(trimmed);
                }
            }

            String hex = hexBuilder.toString();
            if (hex.isEmpty()) return null;

            byte[] exifBytes = hexToBytes(hex);

            Metadata exifMetadata = new Metadata();
            new ExifReader().extract(new com.drew.lang.ByteArrayReader(exifBytes), exifMetadata, ExifReader.JPEG_SEGMENT_PREAMBLE.length());

            int orientation = 1;
            ExifIFD0Directory exifDir = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }

            boolean hasGps = exifMetadata.getFirstDirectoryOfType(GpsDirectory.class) != null;

            return new ImageInfo(orientation, hasGps);
        } catch (Exception e) {
            return null;
        }
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
        if ("webp".equals(format)) {
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

    /**
     * Losslessly strip GPS metadata from a JPEG file.
     * Parses JPEG segments, removes APP1 (EXIF/XMP) and APP2 (ICC),
     * inserts a minimal EXIF APP1 with just the orientation tag,
     * and copies all image data verbatim.
     */
    static void stripJpegGps(File input, File output, int orientation) throws Exception {
        byte[] data = Files.readAllBytes(input.toPath());

        if (data.length < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("Not a valid JPEG file: " + input);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        // Write SOI
        out.write(0xFF);
        out.write(0xD8);

        // Write minimal EXIF with orientation (skip if orientation == 1, the default)
        if (orientation > 1 && orientation <= 8) {
            writeOrientationApp1(out, orientation);
        }

        int pos = 2; // skip SOI
        while (pos < data.length - 1) {
            if ((data[pos] & 0xFF) != 0xFF) {
                // Copy remaining bytes (shouldn't happen in well-formed JPEG header)
                out.write(data, pos, data.length - pos);
                break;
            }

            int marker = data[pos + 1] & 0xFF;

            if (marker == 0xD9) { // EOI
                out.write(data, pos, data.length - pos);
                break;
            }

            if (marker == 0xDA) { // SOS — copy everything from here to end
                out.write(data, pos, data.length - pos);
                break;
            }

            // Read segment length (2 bytes, big-endian, includes itself)
            int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            int segmentSize = 2 + len; // 2 for marker bytes + length (which includes its own 2 bytes)

            // Skip APP1 (0xE1) and APP2 (0xE2) — these contain EXIF, XMP, ICC
            if (marker == 0xE1 || marker == 0xE2) {
                pos += segmentSize;
                continue;
            }

            // Copy all other segments as-is
            out.write(data, pos, segmentSize);
            pos += segmentSize;
        }

        Files.write(output.toPath(), out.toByteArray());
    }

    /**
     * Write a minimal EXIF APP1 segment containing only the orientation tag.
     */
    static void writeOrientationApp1(OutputStream out, int orientation) throws Exception {
        ByteArrayOutputStream exif = new ByteArrayOutputStream();

        // Exif header
        exif.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});

        // TIFF header (big-endian)
        exif.write('M'); exif.write('M');       // byte order: big-endian
        exif.write(0); exif.write(0x2A);        // TIFF magic
        exif.write(0); exif.write(0);
        exif.write(0); exif.write(8);           // offset to IFD0

        // IFD0: 1 entry
        exif.write(0); exif.write(1);           // entry count

        // Orientation tag (12 bytes)
        exif.write(0x01); exif.write(0x12);     // tag = 0x0112 (Orientation)
        exif.write(0); exif.write(3);           // type = SHORT
        exif.write(0); exif.write(0);
        exif.write(0); exif.write(1);           // count = 1
        exif.write(0); exif.write(orientation); // value (SHORT, big-endian)
        exif.write(0); exif.write(0);           // padding

        // Next IFD offset = 0 (no more IFDs)
        exif.write(0); exif.write(0);
        exif.write(0); exif.write(0);

        byte[] exifData = exif.toByteArray();

        // APP1 marker + length
        out.write(0xFF);
        out.write(0xE1);
        int len = exifData.length + 2; // +2 for length field itself
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(exifData);
    }
}
