package com.exifremoval;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExifRemovalTest {

    private static final Logger LOG = Logger.getLogger(ExifRemovalTest.class.getName());

    static final Path outputDir = Path.of("build/test-output");

    @BeforeAll
    static void createOutputDir() throws Exception {
        Files.createDirectories(outputDir);
    }

    // ---- Ground truth: verify our metadata reader agrees with exiftool ----

    @ParameterizedTest(name = "metadata: {0}")
    @CsvFileSource(resources = "/testdata/metadata-ground-truth.csv", numLinesToSkip = 1)
    void testMetadataReading(String resourcePath, boolean expectedHasGps, int expectedOrientation) {
        File input = resourceFile(resourcePath);
        ExifRemoval.ImageInfo info = ExifRemoval.readImageInfo(input);

        assertEquals(expectedHasGps, info.hasGps,
                "GPS detection mismatch for " + resourcePath);
        assertEquals(expectedOrientation, info.orientation,
                "Orientation mismatch for " + resourcePath);
    }

    // ---- GPS stripping: files with GPS get processed, GPS removed ----

    @ParameterizedTest(name = "gps stripped: {0}")
    @CsvSource({
            "gps.jpg,  jpg",
            "gps.png,  png",
            "gps.tiff, tiff",
            "gps.webp, webp",
    })
    void testGpsMetadataStripped(String filename, String ext) throws Exception {
        File input = resourceFile("testdata/format/input/" + filename);
        File expected = resourceFile("testdata/format/expected/" + filename);

        File output = processImage(input, filename);
        copyOriginal(input, filename);
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

    // ---- Rotation + GPS stripping: orientation applied AND GPS removed ----

    @ParameterizedTest(name = "rotation: {0}")
    @CsvSource({
            "rotate.jpg,  jpg",
            "rotate.png,  png",
            "rotate.tiff, tiff",
            "rotate.webp, webp",
    })
    void testRotationApplied(String filename, String ext) throws Exception {
        File input = resourceFile("testdata/format/input/" + filename);
        File expected = resourceFile("testdata/format/expected/" + filename);

        File output = processImage(input, filename);
        copyOriginal(input, filename);
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

    // ---- No-op: files without GPS are left untouched ----

    static Stream<Arguments> noGpsCases() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String prefix : new String[]{"Landscape", "Portrait"}) {
            for (int i = 0; i <= 8; i++) {
                cases.add(Arguments.of(prefix + "_" + i + ".jpg"));
            }
        }
        cases.add(Arguments.of("gps.gif"));
        cases.add(Arguments.of("rotate.gif"));
        return cases.build();
    }

    @ParameterizedTest(name = "no-op: {0}")
    @MethodSource("noGpsCases")
    void testNoGpsLeftUntouched(String filename) throws Exception {
        String subdir = filename.contains("_") ? "orientation" : "format";
        File input = resourceFile("testdata/" + subdir + "/input/" + filename);

        File output = processImage(input, filename);
        copyOriginal(input, filename);

        assertEquals(input.length(), output.length(),
                filename + " should be untouched (same size) but was re-encoded");
        assertArrayEquals(Files.readAllBytes(input.toPath()), Files.readAllBytes(output.toPath()),
                filename + " should be byte-identical to input");
    }

    // ---- Helpers ----

    private File processImage(File input, String outputName) throws Exception {
        File output = outputDir.resolve(addSuffix(outputName, "_processed")).toFile();
        ExifRemoval.process(input, output);
        return output;
    }

    private void copyExpected(File expected, String outputName) throws Exception {
        Path dest = outputDir.resolve(addSuffix(outputName, "_expected"));
        Files.copy(expected.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyOriginal(File input, String outputName) throws Exception {
        Path dest = outputDir.resolve(addSuffix(outputName, "_original"));
        Files.copy(input.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
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

        return Math.sqrt((double) sumSq / count) / 255.0;
    }

    private static void warnIfLarger(File output, File expected, String filename) {
        long outputSize = output.length();
        long expectedSize = expected.length();
        if (outputSize > expectedSize) {
            double pct = ((double) outputSize / expectedSize - 1) * 100;
            LOG.warning(String.format("%s is %.0f%% larger than reference (%,d vs %,d bytes)",
                    filename, pct, outputSize, expectedSize));
        }
    }

    private static void assertNoGpsMetadata(File file) throws Exception {
        Metadata metadata = ImageMetadataReader.readMetadata(file);
        GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
        assertNull(gpsDir, "GPS metadata should be stripped but was found in " + file.getName());
    }

    private static boolean isLossless(String ext) {
        return "png".equals(ext) || "tiff".equals(ext);
    }

    private File resourceFile(String path) {
        var url = getClass().getClassLoader().getResource(path);
        assertNotNull(url, "Test resource not found: " + path);
        return new File(url.getFile());
    }
}
