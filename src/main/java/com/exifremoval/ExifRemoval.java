package com.exifremoval;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.photoshop.PhotoshopDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.xmp.XmpDirectory;

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

        if (!info.hasExif && !info.hasIptc && !info.hasXmp) {
            if (!inputFile.equals(outputFile)) {
                Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        String formatName = detectFormat(inputFile);

        switch (formatName) {
            case "jpeg":
                stripJpegMetadata(inputFile, outputFile, info.orientation);
                return;
            case "png":
                stripPngMetadata(inputFile, outputFile, info.orientation);
                return;
            case "webp":
                stripWebpMetadata(inputFile, outputFile, info.orientation);
                return;
            case "gif":
                stripGifMetadata(inputFile, outputFile);
                return;
            case "tiff":
                // In JPEG/PNG/WebP/GIF, metadata lives in distinct segments or
                // chunks that can be surgically removed. In TIFF, metadata tags
                // share the same IFD structure as image layout tags (strip
                // offsets, dimensions, etc.), so removing them requires rewriting
                // the IFD and recalculating offsets. Instead we decode and
                // re-encode, applying orientation by physically rotating pixels.
                BufferedImage image = ImageIO.read(inputFile);
                if (image == null) {
                    throw new IllegalArgumentException("Could not decode image: " + inputFile);
                }
                BufferedImage oriented = applyOrientation(image, info.orientation);
                writeImage(oriented, formatName, outputFile);
                return;
            default:
                // Unknown format — copy unchanged rather than risk corrupting it.
                if (!inputFile.equals(outputFile)) {
                    Files.copy(inputFile.toPath(), outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return;
        }
    }

    static class ImageInfo {

        final int orientation;
        final boolean hasExif;
        final boolean hasIptc;
        final boolean hasXmp;

        ImageInfo(int orientation, boolean hasExif, boolean hasIptc, boolean hasXmp) {
            this.orientation = orientation;
            this.hasExif = hasExif;
            this.hasIptc = hasIptc;
            this.hasXmp = hasXmp;
        }

    }

    static ImageInfo readImageInfo(File file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);

            boolean hasGps = metadata.getFirstDirectoryOfType(GpsDirectory.class) != null;
            boolean hasIptc = metadata.getFirstDirectoryOfType(IptcDirectory.class) != null
                    || metadata.getFirstDirectoryOfType(PhotoshopDirectory.class) != null;
            boolean hasXmp = metadata.getFirstDirectoryOfType(XmpDirectory.class) != null;

            int orientation = 1;

            // First, try standard EXIF directory (works for JPEG)
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            boolean hasExif = exifDir != null || hasGps;

            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            } else {
                // For PNG: EXIF may be hex-encoded in a tEXt chunk ("Raw profile type exif")
                for (Directory dir : metadata.getDirectoriesOfType(PngDirectory.class)) {
                    for (Tag tag : dir.getTags()) {
                        String desc = tag.getDescription();
                        if (desc != null && desc.contains("Raw profile type exif")) {
                            ImageInfo pngInfo = parseImageInfoFromRawExifProfile(desc);
                            if (pngInfo != null) {
                                return pngInfo;
                            }
                        }
                    }
                }
            }

            return new ImageInfo(orientation, hasExif, hasIptc, hasXmp);
        } catch (Exception e) {
            // No EXIF or unreadable — treat as normal, no metadata
            return new ImageInfo(1, false, false, false);
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
            if (hex.isEmpty()) {
                return null;
            }

            byte[] exifBytes = hexToBytes(hex);

            Metadata exifMetadata = new Metadata();
            new ExifReader().extract(
                    new ByteArrayReader(exifBytes),
                    exifMetadata,
                    ExifReader.JPEG_SEGMENT_PREAMBLE.length());

            int orientation = 1;
            ExifIFD0Directory exifDir = exifMetadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null && exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }

            return new ImageInfo(orientation, true, false, false);
        } catch (Exception e) {
            // Malformed EXIF profile — ignore
            return null;
        }
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream(len / 2);
        for (int i = 0; i + 1 < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                continue;
            }
            out.write((hi << 4) | lo);
        }
        return out.toByteArray();
    }

    /**
     * Apply EXIF orientation transform, matching ImageMagick's -auto-orient behavior.
     *
     * <p>Orientation values:
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
            default:
                break;
        }

        int destW = swapDimensions ? h : w;
        int destH = swapDimensions ? w : h;

        BufferedImage dest = new BufferedImage(
                destW, destH, src.getType() != 0 ? src.getType() : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return dest;
    }

    // Detects image format by reading magic bytes from the file header.
    // See https://en.wikipedia.org/wiki/List_of_file_signatures
    static String detectFormat(File file) throws IOException {
        byte[] header = new byte[12];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(header);
            if (read < 4) {
                return "";
            }
        }
        // JPEG: FF D8 FF — JFIF/Exif SOI marker
        // https://en.wikipedia.org/wiki/JPEG_File_Interchange_Format
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
            return "jpeg";
        }
        // PNG: 89 50 4E 47 — "\x89PNG" signature
        // https://www.libpng.org/pub/png/spec/1.2/PNG-Structure.html
        if (header[0] == (byte) 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G') {
            return "png";
        }
        // GIF: 47 49 46 38 — "GIF8" (GIF87a or GIF89a)
        // https://www.w3.org/Graphics/GIF/spec-gif89a.txt
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return "gif";
        }
        // BMP: 42 4D — "BM"
        // https://en.wikipedia.org/wiki/BMP_file_format
        if (header[0] == 'B' && header[1] == 'M') {
            return "bmp";
        }
        // TIFF: "II" (little-endian) or "MM" (big-endian) followed by 0x002A
        // https://en.wikipedia.org/wiki/TIFF
        if (header[0] == 'I' && header[1] == 'I' && header[2] == 0x2A && header[3] == 0x00) {
            return "tiff";
        }
        if (header[0] == 'M' && header[1] == 'M' && header[2] == 0x00 && header[3] == 0x2A) {
            return "tiff";
        }
        // WebP: RIFF container with "WEBP" FourCC at offset 8
        // https://developers.google.com/speed/webp/docs/riff_container
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return "";
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
        if ("jpeg".equals(format)
                && (image.getType() == BufferedImage.TYPE_INT_ARGB
                || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)) {
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
     * Losslessly strip metadata from a JPEG file.
     * Parses JPEG segments, removes all metadata APP segments (APP1-APP13, APP15)
     * and COM comments, inserts a minimal EXIF APP1 with just the orientation tag,
     * and copies all image data verbatim. Keeps APP0 (JFIF) and APP14 (Adobe color).
     */
    static void stripJpegMetadata(File input, File output, int orientation) throws Exception {
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

            // Strip all metadata APP segments:
            //   APP1  (0xE1) = EXIF, XMP
            //   APP2  (0xE2) = ICC profile, FlashPix
            //   APP3-APP11   = various rare metadata
            //   APP12 (0xEC) = Ducky (Photoshop save-for-web)
            //   APP13 (0xED) = Photoshop/IPTC
            //   APP15 (0xEF) = rare metadata
            // Keep APP0 (0xE0 = JFIF) and APP14 (0xEE = Adobe color info for decoding).
            if (marker >= 0xE1 && marker <= 0xEF && marker != 0xEE) {
                pos += segmentSize;
                continue;
            }

            // Strip COM (0xFE) comments — may contain PII
            if (marker == 0xFE) {
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
     * Losslessly strip metadata from a PNG file.
     * PNG files are a sequence of chunks: [4-byte length][4-byte type][data][4-byte CRC].
     * We keep image-essential chunks (IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, sBIT, pHYs)
     * and strip metadata chunks (eXIf, tEXt, iTXt, zTXt, iCCP) that may contain GPS/EXIF data.
     */
    static void stripPngMetadata(File input, File output, int orientation) throws Exception {
        byte[] data = Files.readAllBytes(input.toPath());

        // PNG signature: 8 bytes
        if (data.length < 8 || data[0] != (byte) 0x89 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') {
            throw new IllegalArgumentException("Not a valid PNG file: " + input);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        // Copy PNG signature
        out.write(data, 0, 8);

        boolean wroteExif = false;
        int pos = 8;
        while (pos + 12 <= data.length) { // minimum chunk: 4 (len) + 4 (type) + 0 (data) + 4 (CRC)
            int chunkLen = readInt(data, pos);
            String chunkType = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int totalChunkSize = 12 + chunkLen; // 4 (len) + 4 (type) + data + 4 (CRC)

            if (isMetadataChunk(chunkType)) {
                // Write orientation eXIf chunk once, right before first stripped chunk
                if (!wroteExif && orientation > 1 && orientation <= 8) {
                    writePngExifChunk(out, orientation);
                    wroteExif = true;
                }
                pos += totalChunkSize;
                continue;
            }

            // Copy chunk as-is
            out.write(data, pos, totalChunkSize);
            pos += totalChunkSize;

            if ("IEND".equals(chunkType)) {
                break;
            }
        }

        Files.write(output.toPath(), out.toByteArray());
    }

    /**
     * Write a PNG eXIf chunk containing only the orientation tag.
     * PNG eXIf chunk data is raw TIFF/EXIF bytes (no "Exif\0\0" prefix).
     */
    static void writePngExifChunk(OutputStream out, int orientation) throws Exception {
        byte[] tiffData = buildOrientationTiff(orientation);
        byte[] chunkType = {'e', 'X', 'I', 'f'};

        // Length (big-endian)
        writeInt(out, tiffData.length);
        // Chunk type
        out.write(chunkType);
        // Data
        out.write(tiffData);
        // CRC32 over type + data
        CRC32 crc = new CRC32();
        crc.update(chunkType);
        crc.update(tiffData);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(OutputStream out, int value) throws Exception {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    /**
     * Build a minimal TIFF structure with just the orientation tag.
     * Shared by PNG (eXIf) and WebP (EXIF) chunk writers.
     */
    static byte[] buildOrientationTiff(int orientation) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();

        // TIFF header (big-endian): byte order, magic, offset to IFD0
        tiff.write('M');
        tiff.write('M');
        tiff.write(0);
        tiff.write(0x2A);
        tiff.write(0);
        tiff.write(0);
        tiff.write(0);
        tiff.write(8);

        // IFD0: 1 entry
        tiff.write(0);
        tiff.write(1);

        // Orientation tag: 0x0112, type=SHORT, count=1
        tiff.write(0x01);
        tiff.write(0x12);
        tiff.write(0);
        tiff.write(3);
        tiff.write(0);
        tiff.write(0);
        tiff.write(0);
        tiff.write(1);
        tiff.write(0);
        tiff.write(orientation);
        tiff.write(0);
        tiff.write(0);

        // Next IFD offset = 0
        tiff.write(0);
        tiff.write(0);
        tiff.write(0);
        tiff.write(0);

        return tiff.toByteArray();
    }

    private static boolean isMetadataChunk(String chunkType) {
        switch (chunkType) {
            case "eXIf": // EXIF data
            case "tEXt": // text metadata (may contain raw EXIF profile)
            case "iTXt": // international text (may contain XMP)
            case "zTXt": // compressed text
            case "iCCP": // ICC color profile
                return true;
            default:
                return false;
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * Losslessly strip metadata from a WebP file.
     * WebP uses RIFF container: "RIFF" [size] "WEBP" followed by chunks.
     * Each chunk: [4-byte FourCC][4-byte size][data][optional pad byte].
     * We strip EXIF and XMP chunks, keep everything else.
     */
    static void stripWebpMetadata(File input, File output, int orientation) throws Exception {
        byte[] data = Files.readAllBytes(input.toPath());

        // RIFF header: "RIFF" + 4-byte size + "WEBP"
        if (data.length < 12 || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') {
            throw new IllegalArgumentException("Not a valid WebP file: " + input);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        // Write RIFF header placeholder (we'll fix the size at the end)
        out.write(data, 0, 12); // "RIFF" + size + "WEBP"

        boolean wroteExif = false;
        int pos = 12;
        while (pos + 8 <= data.length) {
            String fourCC = new String(data, pos, 4, StandardCharsets.ISO_8859_1);
            int chunkSize = readIntLE(data, pos + 4);
            int paddedSize = (chunkSize + 1) & ~1; // chunks are padded to even size
            int totalChunkSize = 8 + paddedSize; // 4 (FourCC) + 4 (size) + padded data

            if ("EXIF".equals(fourCC) || "XMP ".equals(fourCC)) {
                // Write orientation EXIF chunk once, replacing the first stripped chunk
                if (!wroteExif && orientation > 1 && orientation <= 8) {
                    writeWebpExifChunk(out, orientation);
                    wroteExif = true;
                }
                pos += totalChunkSize;
                continue;
            }

            // Copy chunk as-is
            int bytesToCopy = Math.min(totalChunkSize, data.length - pos);
            out.write(data, pos, bytesToCopy);
            pos += totalChunkSize;
        }

        // Fix RIFF size field (total file size - 8)
        byte[] result = out.toByteArray();
        int riffSize = result.length - 8;
        result[4] = (byte) (riffSize & 0xFF);
        result[5] = (byte) ((riffSize >> 8) & 0xFF);
        result[6] = (byte) ((riffSize >> 16) & 0xFF);
        result[7] = (byte) ((riffSize >> 24) & 0xFF);

        Files.write(output.toPath(), result);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Write a WebP EXIF chunk containing only the orientation tag.
     * WebP EXIF chunk: "EXIF" + little-endian size + raw EXIF/TIFF bytes.
     */
    static void writeWebpExifChunk(OutputStream out, int orientation) throws Exception {
        byte[] tiffData = buildOrientationTiff(orientation);

        // FourCC
        out.write(new byte[] {'E', 'X', 'I', 'F'});
        // Size (little-endian)
        int size = tiffData.length;
        out.write(size & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 24) & 0xFF);
        // Data
        out.write(tiffData);
        // Pad to even size
        if (size % 2 != 0) {
            out.write(0);
        }
    }

    /**
     * Losslessly strip metadata from a GIF file.
     * GIF stores metadata in Extension blocks after the Logical Screen Descriptor.
     * We strip Application Extensions (21 FF — XMP, ICC profiles) and
     * Comment Extensions (21 FE — may contain PII). Image data and Graphics
     * Control Extensions are copied verbatim.
     */
    static void stripGifMetadata(File input, File output) throws Exception {
        byte[] data = Files.readAllBytes(input.toPath());

        // GIF header: "GIF87a" or "GIF89a" (6 bytes)
        if (data.length < 6 || data[0] != 'G' || data[1] != 'I' || data[2] != 'F') {
            throw new IllegalArgumentException("Not a valid GIF file: " + input);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        // Copy Header (6 bytes) + Logical Screen Descriptor (7 bytes)
        out.write(data, 0, 13);

        // Copy Global Color Table if present
        int packed = data[10] & 0xFF;
        boolean hasGct = (packed & 0x80) != 0;
        int pos = 13;
        if (hasGct) {
            int gctSize = 3 * (1 << ((packed & 0x07) + 1));
            out.write(data, pos, gctSize);
            pos += gctSize;
        }

        // Process blocks
        while (pos < data.length) {
            int blockType = data[pos] & 0xFF;

            if (blockType == 0x3B) { // Trailer
                out.write(0x3B);
                break;
            }

            if (blockType == 0x2C) { // Image Descriptor
                // Copy Image Descriptor (10 bytes min)
                out.write(data, pos, 10);
                int imgPacked = data[pos + 9] & 0xFF;
                pos += 10;

                // Copy Local Color Table if present
                if ((imgPacked & 0x80) != 0) {
                    int lctSize = 3 * (1 << ((imgPacked & 0x07) + 1));
                    out.write(data, pos, lctSize);
                    pos += lctSize;
                }

                // Copy LZW Minimum Code Size
                out.write(data[pos] & 0xFF);
                pos++;

                // Copy sub-blocks (data blocks)
                pos = copySubBlocks(data, pos, out);
                continue;
            }

            if (blockType == 0x21) { // Extension
                int label = data[pos + 1] & 0xFF;

                if (label == 0xFF || label == 0xFE) {
                    // Application Extension (0xFF) or Comment Extension (0xFE) — skip
                    pos += 2;
                    // Skip block size + data for Application Extension header
                    if (label == 0xFF) {
                        int blockSize = data[pos] & 0xFF;
                        pos += 1 + blockSize; // skip block size byte + app identifier
                    }
                    // Skip sub-blocks
                    pos = skipSubBlocks(data, pos);
                    continue;
                }

                // Other extensions (e.g. Graphics Control 0xF9) — copy
                out.write(data, pos, 2); // introducer + label
                pos += 2;
                pos = copySubBlocks(data, pos, out);
                continue;
            }

            // Unknown block type — copy byte and move on
            out.write(data[pos] & 0xFF);
            pos++;
        }

        Files.write(output.toPath(), out.toByteArray());
    }

    /** Copy GIF sub-blocks (size + data pairs) until a zero-length terminator. */
    private static int copySubBlocks(byte[] data, int pos, ByteArrayOutputStream out) {
        while (pos < data.length) {
            int size = data[pos] & 0xFF;
            out.write(size);
            pos++;
            if (size == 0) {
                break;
            }
            out.write(data, pos, size);
            pos += size;
        }
        return pos;
    }

    /** Skip GIF sub-blocks until a zero-length terminator. */
    private static int skipSubBlocks(byte[] data, int pos) {
        while (pos < data.length) {
            int size = data[pos] & 0xFF;
            pos++;
            if (size == 0) {
                break;
            }
            pos += size;
        }
        return pos;
    }

    /**
     * Write a minimal EXIF APP1 segment containing only the orientation tag.
     * JPEG APP1: FF E1 + length + "Exif\0\0" + TIFF data.
     */
    static void writeOrientationApp1(OutputStream out, int orientation) throws Exception {
        byte[] tiffData = buildOrientationTiff(orientation);
        byte[] prefix = {'E', 'x', 'i', 'f', 0, 0};

        out.write(0xFF);
        out.write(0xE1);
        int len = prefix.length + tiffData.length + 2; // +2 for length field itself
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(prefix);
        out.write(tiffData);
    }
}
