package com.exifremoval;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

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
        String formatName = detectFormat(inputFile);

        // HEIC is excluded from this gate because metadata-extractor does not
        // detect XMP in HEIC files; the strip method handles it at the
        // container level by removing all Exif/mime items from iinf/iloc/iref.
        if (!info.hasExif && !info.hasIptc && !info.hasXmp
                && !"heic".equals(formatName)) {
            if (!inputFile.equals(outputFile)) {
                Files.copy(inputFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

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
            case "heic":
                stripHeicMetadata(inputFile, outputFile);
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
                ImageIO.write(oriented, "tiff", outputFile);
                return;
            default:
                // Unknown format — copy unchanged rather than risk corrupting it
                if (!inputFile.equals(outputFile)) {
                    Files.copy(inputFile.toPath(), outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
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

    // We parse metadata primarily to read the orientation tag, which the
    // strip methods need to preserve. Since the parse already loads the full
    // directory tree, we also record which metadata types (EXIF, IPTC, XMP)
    // are present so we can skip files that have nothing to strip.
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
    private static ImageInfo parseImageInfoFromRawExifProfile(String rawProfile) {
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

    private static byte[] hexToBytes(String hex) {
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
    private static BufferedImage applyOrientation(BufferedImage src, int orientation) {
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
    private static String detectFormat(File file) throws IOException {
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
        // HEIC/HEIF: ISOBMFF container with "ftyp" box type and HEIF major brand
        // https://nokiatech.github.io/heif/technical.html
        if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            String brand = new String(header, 8, 4, StandardCharsets.ISO_8859_1);
            if ("heic".equals(brand) || "heix".equals(brand) || "mif1".equals(brand)) {
                return "heic";
            }
        }
        return "";
    }

    /**
     * Losslessly strip metadata from a JPEG file.
     * Parses JPEG segments, removes all metadata APP segments (APP1-APP13, APP15)
     * and COM comments, inserts a minimal EXIF APP1 with just the orientation tag,
     * and copies all image data verbatim. Keeps APP0 (JFIF) and APP14 (Adobe color).
     */
    private static void stripJpegMetadata(File input, File output, int orientation) throws Exception {
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
    private static void stripPngMetadata(File input, File output, int orientation) throws Exception {
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
            int chunkLen = ByteBuffer.wrap(data, pos, 4).getInt();
            String chunkType = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int totalChunkSize = 12 + chunkLen; // 4 (len) + 4 (type) + data + 4 (CRC)

            // Strip PNG chunks that carry metadata
            if ("eXIf".equals(chunkType) // EXIF data
                    || "tEXt".equals(chunkType) // text (may contain raw EXIF profile)
                    || "iTXt".equals(chunkType) // international text (may contain XMP)
                    || "zTXt".equals(chunkType) // compressed text
                    || "iCCP".equals(chunkType)) { // ICC color profile
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
    private static void writePngExifChunk(OutputStream out, int orientation) throws Exception {
        byte[] tiffData = buildOrientationTiff(orientation);
        byte[] chunkType = {'e', 'X', 'I', 'f'};
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeInt(tiffData.length);
        dos.write(chunkType);
        dos.write(tiffData);

        CRC32 crc = new CRC32();
        crc.update(chunkType);
        crc.update(tiffData);
        dos.writeInt((int) crc.getValue());
    }

    /**
     * Build a minimal TIFF structure with just the orientation tag.
     * EXIF is encoded as a TIFF byte structure regardless of the container
     * format — JPEG (APP1), PNG (eXIf chunk), and WebP (EXIF RIFF chunk)
     * all wrap these same raw TIFF bytes.
     */
    private static byte[] buildOrientationTiff(int orientation) {
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

    /**
     * Losslessly strip metadata from a WebP file.
     * WebP uses RIFF container: "RIFF" [size] "WEBP" followed by chunks.
     * Each chunk: [4-byte FourCC][4-byte size][data][optional pad byte].
     * We strip EXIF and XMP chunks, keep everything else.
     */
    private static void stripWebpMetadata(File input, File output, int orientation) throws Exception {
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
            int chunkSize = ByteBuffer.wrap(data, pos + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
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

    /**
     * Write a WebP EXIF chunk containing only the orientation tag.
     * WebP EXIF chunk: "EXIF" + little-endian size + raw EXIF/TIFF bytes.
     */
    private static void writeWebpExifChunk(OutputStream out, int orientation) throws Exception {
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
    private static void stripGifMetadata(File input, File output) throws Exception {
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
                pos = copyGifSubBlocks(data, pos, out);
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
                    // Skip sub-blocks (size + data pairs until zero terminator)
                    while (pos < data.length) {
                        int size = data[pos] & 0xFF;
                        pos++;
                        if (size == 0) {
                            break;
                        }
                        pos += size;
                    }
                    continue;
                }

                // Other extensions (e.g. Graphics Control 0xF9) — copy
                out.write(data, pos, 2); // introducer + label
                pos += 2;
                pos = copyGifSubBlocks(data, pos, out);
                continue;
            }

            // Unknown block type — copy byte and move on
            out.write(data[pos] & 0xFF);
            pos++;
        }

        Files.write(output.toPath(), out.toByteArray());
    }

    /** Copy GIF sub-blocks (size + data pairs) until a zero-length terminator. */
    private static int copyGifSubBlocks(byte[] data, int pos, ByteArrayOutputStream out) {
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

    /**
     * Losslessly strip metadata from a HEIC/HEIF file.
     * HEIC uses ISOBMFF (ISO Base Media File Format) where metadata (EXIF, XMP) is
     * stored as separate items in mdat, referenced via iinf/iloc in the meta box.
     * We rebuild the meta box, filtering out metadata item entries from iinf, iloc,
     * and iref, while copying all other boxes (ftyp, mdat, iprp) verbatim.
     * Orientation is preserved via irot/imir property boxes in iprp (not EXIF tags).
     */
    private static void stripHeicMetadata(File input, File output) throws Exception {
        byte[] data = Files.readAllBytes(input.toPath());

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);

        int pos = 0;
        while (pos < data.length) {
            int boxSize = isobmffBoxSize(data, pos);
            String boxType = isobmffBoxType(data, pos);

            if (boxSize == 0) {
                // Box extends to end of file
                boxSize = data.length - pos;
            }

            if ("meta".equals(boxType)) {
                rebuildHeicMetaBox(data, pos, pos + boxSize, out);
            } else {
                out.write(data, pos, boxSize);
            }
            pos += boxSize;
        }

        Files.write(output.toPath(), out.toByteArray());
    }

    /** Read an ISOBMFF box size, handling extended size (size field == 1). */
    private static int isobmffBoxSize(byte[] data, int pos) {
        int size32 = ByteBuffer.wrap(data, pos, 4).getInt();
        if (size32 == 1 && pos + 16 <= data.length) {
            // Extended size: 8-byte size follows the type field
            return (int) ByteBuffer.wrap(data, pos + 8, 8).getLong();
        }
        return size32;
    }

    /** Read a 4-byte ISOBMFF box type as a String. */
    private static String isobmffBoxType(byte[] data, int pos) {
        return new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
    }

    /** Return the header length for an ISOBMFF box (8 normal, 16 extended). */
    private static int isobmffHeaderLen(byte[] data, int pos) {
        return ByteBuffer.wrap(data, pos, 4).getInt() == 1 ? 16 : 8;
    }

    /**
     * Rebuild the HEIC meta box, removing metadata items (EXIF, XMP) from
     * iinf, iloc, and iref while preserving everything else (hdlr, pitm, iprp).
     */
    private static void rebuildHeicMetaBox(byte[] data, int metaStart, int metaEnd,
                                           ByteArrayOutputStream out) throws IOException {
        int headerLen = isobmffHeaderLen(data, metaStart);
        // meta is a FullBox: 4 bytes version+flags after the box header
        int vfStart = metaStart + headerLen;
        int childrenStart = vfStart + 4;

        // First pass: collect metadata item IDs from iinf
        Set<Integer> metadataItemIds = new HashSet<>();
        int pos = childrenStart;
        while (pos < metaEnd) {
            int boxSize = isobmffBoxSize(data, pos);
            if (boxSize == 0) {
                boxSize = metaEnd - pos;
            }
            if ("iinf".equals(isobmffBoxType(data, pos))) {
                collectHeicMetadataItemIds(data, pos, pos + boxSize, metadataItemIds);
            }
            pos += boxSize;
        }

        // Second pass: rebuild children, filtering metadata from iinf/iloc/iref
        ByteArrayOutputStream children = new ByteArrayOutputStream();
        pos = childrenStart;
        while (pos < metaEnd) {
            int boxSize = isobmffBoxSize(data, pos);
            String boxType = isobmffBoxType(data, pos);
            if (boxSize == 0) {
                boxSize = metaEnd - pos;
            }
            int boxEnd = pos + boxSize;

            if ("iinf".equals(boxType)) {
                rebuildHeicIinf(data, pos, boxEnd, metadataItemIds, children);
            } else if ("iloc".equals(boxType)) {
                rebuildHeicIloc(data, pos, boxEnd, metadataItemIds, children);
            } else if ("iref".equals(boxType)) {
                rebuildHeicIref(data, pos, boxEnd, metadataItemIds, children);
            } else {
                // hdlr, pitm, iprp, idat, grpl, etc. — copy verbatim
                children.write(data, pos, boxSize);
            }
            pos = boxEnd;
        }

        // Write rebuilt meta box with corrected size
        byte[] childBytes = children.toByteArray();
        int metaBoxSize = headerLen + 4 + childBytes.length;
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(metaBoxSize);
        dos.write(new byte[] {'m', 'e', 't', 'a'});
        dos.write(data, vfStart, 4); // version + flags
        dos.write(childBytes);
    }

    /**
     * Scan iinf to find item IDs of type "Exif" or "mime" (XMP).
     * iinf is a FullBox containing nested infe FullBox entries.
     */
    private static void collectHeicMetadataItemIds(byte[] data, int iinfStart, int iinfEnd,
                                                   Set<Integer> ids) {
        int headerLen = isobmffHeaderLen(data, iinfStart);
        int version = data[iinfStart + headerLen] & 0xFF;

        // entry_count: uint16 (v0) or uint32 (v>0)
        int childPos = iinfStart + headerLen + 4 + (version == 0 ? 2 : 4);

        while (childPos < iinfEnd) {
            int infeSize = isobmffBoxSize(data, childPos);
            if (infeSize == 0) {
                break;
            }
            parseHeicInfe(data, childPos, childPos + infeSize, ids);
            childPos += infeSize;
        }
    }

    /** Parse a single infe FullBox to extract item_ID and item_type. */
    private static void parseHeicInfe(byte[] data, int start, int end,
                                      Set<Integer> metadataItemIds) {
        int headerLen = isobmffHeaderLen(data, start);
        int infeVersion = data[start + headerLen] & 0xFF;

        if (infeVersion < 2) {
            return; // v0/v1 infe don't have item_type FourCC
        }

        int fp = start + headerLen + 4; // skip version(1) + flags(3)

        int itemId;
        if (infeVersion == 2) {
            itemId = ByteBuffer.wrap(data, fp, 2).getShort() & 0xFFFF;
            fp += 2;
        } else { // v3+
            itemId = ByteBuffer.wrap(data, fp, 4).getInt();
            fp += 4;
        }

        fp += 2; // skip item_protection_index

        if (fp + 4 <= end) {
            String itemType = new String(data, fp, 4, StandardCharsets.ISO_8859_1);
            if ("Exif".equals(itemType) || "mime".equals(itemType)) {
                metadataItemIds.add(itemId);
            }
        }
    }

    /** Read item_ID from an infe box (used by rebuildHeicIinf). */
    private static int readHeicInfeItemId(byte[] data, int start) {
        int headerLen = isobmffHeaderLen(data, start);
        int infeVersion = data[start + headerLen] & 0xFF;
        int fp = start + headerLen + 4;
        if (infeVersion >= 3) {
            return ByteBuffer.wrap(data, fp, 4).getInt();
        }
        return ByteBuffer.wrap(data, fp, 2).getShort() & 0xFFFF;
    }

    /** Rebuild iinf box, skipping infe entries for metadata items. */
    private static void rebuildHeicIinf(byte[] data, int start, int end,
                                        Set<Integer> metadataItemIds,
                                        ByteArrayOutputStream out) throws IOException {
        int headerLen = isobmffHeaderLen(data, start);
        int version = data[start + headerLen] & 0xFF;
        int countFieldSize = (version == 0) ? 2 : 4;

        // entry_count position
        int childPos = start + headerLen + 4 + countFieldSize;

        // Collect non-metadata infe boxes
        ByteArrayOutputStream infeBytes = new ByteArrayOutputStream();
        int keptCount = 0;
        while (childPos < end) {
            int infeSize = isobmffBoxSize(data, childPos);
            if (infeSize == 0) {
                break;
            }
            int itemId = readHeicInfeItemId(data, childPos);
            if (!metadataItemIds.contains(itemId)) {
                infeBytes.write(data, childPos, infeSize);
                keptCount++;
            }
            childPos += infeSize;
        }

        // Write rebuilt iinf box
        byte[] kept = infeBytes.toByteArray();
        int iinfBoxSize = headerLen + 4 + countFieldSize + kept.length;
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(iinfBoxSize);
        dos.write(new byte[] {'i', 'i', 'n', 'f'});
        dos.write(data, start + headerLen, 4); // version + flags
        if (version == 0) {
            dos.writeShort(keptCount);
        } else {
            dos.writeInt(keptCount);
        }
        dos.write(kept);
    }

    /**
     * Rebuild iloc box, skipping entries for metadata items.
     * iloc has variable-length fields configured by size bytes in its header.
     */
    private static void rebuildHeicIloc(byte[] data, int start, int end,
                                        Set<Integer> metadataItemIds,
                                        ByteArrayOutputStream out) throws IOException {
        int headerLen = isobmffHeaderLen(data, start);
        int version = data[start + headerLen] & 0xFF;

        int fp = start + headerLen + 4; // after version+flags

        int sizeByte1 = data[fp] & 0xFF;
        int offsetSize = (sizeByte1 >> 4) & 0xF;
        int lengthSize = sizeByte1 & 0xF;

        int sizeByte2 = data[fp + 1] & 0xFF;
        int baseOffsetSize = (sizeByte2 >> 4) & 0xF;
        int indexSize = (version >= 1) ? (sizeByte2 & 0xF) : 0;

        int countPos = fp + 2;
        int itemCount;
        int itemIdSize;
        if (version < 2) {
            itemCount = ByteBuffer.wrap(data, countPos, 2).getShort() & 0xFFFF;
            itemIdSize = 2;
        } else {
            itemCount = ByteBuffer.wrap(data, countPos, 4).getInt();
            itemIdSize = 4;
        }
        int countFieldSize = (version < 2) ? 2 : 4;
        int entryPos = countPos + countFieldSize;

        // Parse entries, collect non-metadata ones as raw byte spans
        ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
        int keptCount = 0;
        for (int i = 0; i < itemCount; i++) {
            int entryStart = entryPos;

            // item_ID
            int itemId;
            if (version < 2) {
                itemId = ByteBuffer.wrap(data, entryPos, 2).getShort() & 0xFFFF;
            } else {
                itemId = ByteBuffer.wrap(data, entryPos, 4).getInt();
            }
            entryPos += itemIdSize;

            // construction_method (v>=1)
            if (version >= 1) {
                entryPos += 2;
            }

            entryPos += 2; // data_reference_index
            entryPos += baseOffsetSize; // base_offset

            int extentCount = ByteBuffer.wrap(data, entryPos, 2).getShort() & 0xFFFF;
            entryPos += 2;

            for (int e = 0; e < extentCount; e++) {
                if (version >= 1 && indexSize > 0) {
                    entryPos += indexSize;
                }
                entryPos += offsetSize;
                entryPos += lengthSize;
            }

            if (!metadataItemIds.contains(itemId)) {
                entryBytes.write(data, entryStart, entryPos - entryStart);
                keptCount++;
            }
        }

        // Write rebuilt iloc box
        byte[] entries = entryBytes.toByteArray();
        int ilocBoxSize = headerLen + 4 + 2 + countFieldSize + entries.length;
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(ilocBoxSize);
        dos.write(new byte[] {'i', 'l', 'o', 'c'});
        dos.write(data, start + headerLen, 4); // version + flags
        dos.writeByte(sizeByte1);
        dos.writeByte(sizeByte2);
        if (version < 2) {
            dos.writeShort(keptCount);
        } else {
            dos.writeInt(keptCount);
        }
        dos.write(entries);
    }

    /**
     * Rebuild iref box, skipping reference boxes whose from_item_ID is a metadata item.
     * iref is a FullBox containing nested typed reference boxes.
     */
    private static void rebuildHeicIref(byte[] data, int start, int end,
                                        Set<Integer> metadataItemIds,
                                        ByteArrayOutputStream out) throws IOException {
        int headerLen = isobmffHeaderLen(data, start);
        int version = data[start + headerLen] & 0xFF;
        int childPos = start + headerLen + 4; // after version+flags

        ByteArrayOutputStream refBytes = new ByteArrayOutputStream();
        while (childPos < end) {
            int refSize = isobmffBoxSize(data, childPos);
            if (refSize == 0) {
                break;
            }
            int refHeaderLen = isobmffHeaderLen(data, childPos);

            int fromItemId;
            if (version == 0) {
                fromItemId = ByteBuffer.wrap(data, childPos + refHeaderLen, 2).getShort() & 0xFFFF;
            } else {
                fromItemId = ByteBuffer.wrap(data, childPos + refHeaderLen, 4).getInt();
            }

            if (!metadataItemIds.contains(fromItemId)) {
                refBytes.write(data, childPos, refSize);
            }
            childPos += refSize;
        }

        byte[] refs = refBytes.toByteArray();
        if (refs.length == 0) {
            return; // omit empty iref box entirely
        }

        int irefBoxSize = headerLen + 4 + refs.length;
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(irefBoxSize);
        dos.write(new byte[] {'i', 'r', 'e', 'f'});
        dos.write(data, start + headerLen, 4); // version + flags
        dos.write(refs);
    }

    /**
     * Write a minimal EXIF APP1 segment containing only the orientation tag.
     * JPEG APP1: FF E1 + length + "Exif\0\0" + TIFF data.
     */
    private static void writeOrientationApp1(OutputStream out, int orientation) throws Exception {
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
