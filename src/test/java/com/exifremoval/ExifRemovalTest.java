package com.exifremoval;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExifRemovalTest {

    static final Path tempDir = Path.of("build/test-output");

    @BeforeAll
    static void createOutputDir() throws Exception {
        Files.createDirectories(tempDir);
    }

    // ---- Orientation tests (18 cases) ----

    static Stream<Arguments> orientationCases() {
        List<Arguments> cases = new ArrayList<>();
        for (String prefix : new String[]{"Landscape", "Portrait"}) {
            for (int i = 0; i <= 8; i++) {
                cases.add(Arguments.of(prefix + "_" + i + ".jpg"));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "orientation: {0}")
    @MethodSource("orientationCases")
    void testOrientationCorrection(String filename) throws Exception {
        File input = resourceFile("testdata/orientation/input/" + filename);
        File expected = resourceFile("testdata/orientation/expected/" + filename);

        File output = processImage(input, filename);
        copyExpected(expected, filename);

        BufferedImage actualImg = ImageIO.read(output);
        BufferedImage expectedImg = ImageIO.read(expected);

        assertNotNull(actualImg, "Failed to read actual output");
        assertNotNull(expectedImg, "Failed to read expected output");

        assertEquals(expectedImg.getWidth(), actualImg.getWidth(),
                "Width mismatch for " + filename);
        assertEquals(expectedImg.getHeight(), actualImg.getHeight(),
                "Height mismatch for " + filename);

        double rmse = computeRMSE(expectedImg, actualImg);
        assertTrue(rmse < 0.02,
                "RMSE too high for " + filename + ": " + rmse);

        warnIfLarger(output, expected, filename);
    }

    // ---- GPS metadata stripping tests (5 cases) ----

    static Stream<Arguments> gpsCases() {
        return Stream.of("jpg", "png", "gif", "tiff", "webp")
                .map(ext -> Arguments.of("gps." + ext, ext));
    }

    @ParameterizedTest(name = "gps stripped: {0}")
    @MethodSource("gpsCases")
    void testGpsMetadataStripped(String filename, String ext) throws Exception {
        File input = resourceFile("testdata/format/input/" + filename);
        File expected = resourceFile("testdata/format/expected/" + filename);

        File output = processImage(input, filename);
        copyExpected(expected, filename);

        assertNoGpsMetadata(output);

        BufferedImage actualImg = ImageIO.read(output);
        BufferedImage expectedImg = ImageIO.read(expected);

        assertNotNull(actualImg, "Failed to read actual output");
        assertNotNull(expectedImg, "Failed to read expected output");

        assertEquals(expectedImg.getWidth(), actualImg.getWidth(),
                "Width mismatch for " + filename);
        assertEquals(expectedImg.getHeight(), actualImg.getHeight(),
                "Height mismatch for " + filename);

        double rmse = computeRMSE(expectedImg, actualImg);

        if (isLossless(ext)) {
            assertEquals(0.0, rmse, 1e-9,
                    "Lossless format " + filename + " should have RMSE == 0 but got " + rmse);
        } else {
            assertTrue(rmse < 0.02,
                    "RMSE too high for " + filename + ": " + rmse);
        }

        warnIfLarger(output, expected, filename);
    }

    // ---- Rotation tests (5 cases) ----

    static Stream<Arguments> rotationCases() {
        return Stream.of("jpg", "png", "gif", "tiff", "webp")
                .map(ext -> Arguments.of("rotate." + ext, ext));
    }

    @ParameterizedTest(name = "rotation: {0}")
    @MethodSource("rotationCases")
    void testRotationApplied(String filename, String ext) throws Exception {
        File input = resourceFile("testdata/format/input/" + filename);
        File expected = resourceFile("testdata/format/expected/" + filename);

        File output = processImage(input, filename);
        copyExpected(expected, filename);

        assertNoGpsMetadata(output);

        BufferedImage actualImg = ImageIO.read(output);
        BufferedImage expectedImg = ImageIO.read(expected);

        assertNotNull(actualImg, "Failed to read actual output");
        assertNotNull(expectedImg, "Failed to read expected output");

        assertEquals(expectedImg.getWidth(), actualImg.getWidth(),
                "Width mismatch for " + filename);
        assertEquals(expectedImg.getHeight(), actualImg.getHeight(),
                "Height mismatch for " + filename);

        double rmse = computeRMSE(expectedImg, actualImg);

        if (isLossless(ext)) {
            assertEquals(0.0, rmse, 1e-9,
                    "Lossless format " + filename + " should have RMSE == 0 but got " + rmse);
        } else {
            assertTrue(rmse < 0.02,
                    "RMSE too high for " + filename + ": " + rmse);
        }

        warnIfLarger(output, expected, filename);
    }

    // ---- Helpers ----

    private File processImage(File input, String outputName) throws Exception {
        int orientation = ExifRemoval.readOrientation(input);
        BufferedImage image = ImageIO.read(input);
        assertNotNull(image, "Could not decode input: " + input);

        BufferedImage oriented = ExifRemoval.applyOrientation(image, orientation);

        String format = ExifRemoval.getFormatName(input.getName());
        File output = tempDir.resolve(addSuffix(outputName, "_processed")).toFile();
        ExifRemoval.writeImage(oriented, format, output);
        return output;
    }

    private void copyExpected(File expected, String outputName) throws Exception {
        Path dest = tempDir.resolve(addSuffix(outputName, "_expected"));
        Files.copy(expected.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String addSuffix(String filename, String suffix) {
        int dot = filename.lastIndexOf('.');
        return filename.substring(0, dot) + suffix + filename.substring(dot);
    }

    private static double computeRMSE(BufferedImage a, BufferedImage b) {
        int w = a.getWidth();
        int h = a.getHeight();
        if (w != b.getWidth() || h != b.getHeight()) {
            throw new IllegalArgumentException("Image dimensions differ");
        }

        long sumSq = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgbA = a.getRGB(x, y);
                int rgbB = b.getRGB(x, y);

                int rA = (rgbA >> 16) & 0xFF;
                int gA = (rgbA >> 8) & 0xFF;
                int bA = rgbA & 0xFF;

                int rB = (rgbB >> 16) & 0xFF;
                int gB = (rgbB >> 8) & 0xFF;
                int bB = rgbB & 0xFF;

                sumSq += (rA - rB) * (rA - rB)
                       + (gA - gB) * (gA - gB)
                       + (bA - bB) * (bA - bB);
                count += 3;
            }
        }

        // Normalize to 0-1 range (max channel value is 255)
        return Math.sqrt((double) sumSq / count) / 255.0;
    }

    private static void assertNotLarger(File output, File expected, String filename) {
        long outputSize = output.length();
        long expectedSize = expected.length();
        double ratio = (double) outputSize / expectedSize;
        assertTrue(ratio <= 1.10,
                String.format("%s is %.0f%% larger than reference (%,d vs %,d bytes)",
                        filename, (ratio - 1) * 100, outputSize, expectedSize));
    }

    /** Same check but non-fatal — logs a warning instead of failing. */
    private static void warnIfLarger(File output, File expected, String filename) {
        long outputSize = output.length();
        long expectedSize = expected.length();
        if (outputSize > expectedSize) {
            double pct = ((double) outputSize / expectedSize - 1) * 100;
            System.err.printf("WARNING: %s is %.0f%% larger than reference (%,d vs %,d bytes)%n",
                    filename, pct, outputSize, expectedSize);
        }
    }

    private static void assertNoGpsMetadata(File file) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        assertNull(gpsDir, "GPS metadata should be stripped but was found in " + file.getName());
    }

    private static boolean isLossless(String ext) {
        return "png".equals(ext) || "tiff".equals(ext) || "gif".equals(ext);
    }

    private File resourceFile(String path) {
        var url = getClass().getClassLoader().getResource(path);
        assertNotNull(url, "Test resource not found: " + path);
        return new File(url.getFile());
    }
}
