package edu.camserver.app.service.fits;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Needs CFITSIO's fpack and imcopy on the PATH; skipped otherwise. */
class RiceArchiverTest {

    @TempDir
    Path dir;
    RiceArchiver archiver;

    @BeforeEach
    void setUp() {
        archiver = new RiceArchiver("fpack", "imcopy", 2, Duration.ofMinutes(2), dir.resolve("tmp"));
        RiceArchiver.Availability availability = archiver.probe();
        Assumptions.assumeTrue(availability.available(), "fpack/imcopy not installed: " + availability.detail());
    }

    @Test
    void compressesShiftsVerifiesAndRestores() throws IOException {
        byte[] original = TestFits.cameraLike(300, 120, 3, 11);
        Path plain = TestFits.write(dir, "frame.fits", original);
        Path fz = dir.resolve("frame.fits.fz");

        RiceArchiver.Result result = archiver.compress(plain, fz);

        assertEquals(2, result.shift());
        assertTrue(Files.isRegularFile(fz));
        assertTrue(Files.isRegularFile(plain), "the source is the caller's to delete");
        assertTrue(result.bytes() < original.length * 0.8, "Rice + shift should shrink noisy 14-bit data well below 80%");
        assertEquals(Files.getLastModifiedTime(plain), Files.getLastModifiedTime(fz));
        assertNoTempFiles();

        FitsLayout layout = FitsLayout.inspect(fz);
        assertEquals(1, layout.compressedImageHdu());
        assertEquals(-1, layout.plainImageHdu());

        ShiftedFits.Scan expected = ShiftedFits.scan(plain, 0);
        ShiftedFits.Scan restored;
        try (InputStream in = archiver.openRestored(fz)) {
            restored = ShiftedFits.scanStream(in);
        }
        assertTrue(expected.matches(restored), "restored image is pixel-identical: " + expected + " vs " + restored);

        Path back = dir.resolve("frame.back.fits");
        archiver.decompress(fz, back);
        assertTrue(expected.matches(ShiftedFits.scan(back, 0)));
        FitsHeader header = FitsLayout.inspect(back).primary();
        assertEquals(1, header.longValue("BSCALE").orElseThrow());
        assertFalse(header.has(ShiftedFits.KEYWORD));
        assertFalse(header.has("DATASUM"));
    }

    @Test
    void framesThatCannotBeShiftedAreStillRiceCompressed() throws IOException {
        Path plain = TestFits.write(dir, "odd.fits", TestFits.plain16(200, 100, 1, i -> (i * 7919) & 0x0fff));
        Path fz = dir.resolve("odd.fits.fz");
        RiceArchiver.Result result = archiver.compress(plain, fz);
        assertEquals(0, result.shift());
        ShiftedFits.Scan restored;
        try (InputStream in = archiver.openRestored(fz)) {
            restored = ShiftedFits.scanStream(in);
        }
        assertTrue(ShiftedFits.scan(plain, 0).matches(restored));
    }

    @Test
    void sourcesAlreadyTileCompressedUnderAPlainNameAreReencoded() throws Exception {
        // The cameras uploaded 2025 frames as astropy CompImageHDU files named *.fits: an empty
        // primary HDU plus a RICE_1 binary table. fpack produces the same layout.
        byte[] original = TestFits.cameraLike(256, 64, 3, 5);
        Path reference = TestFits.write(dir, "reference.fits", original);
        Path packed = dir.resolve("packed.fits");
        Process p = new ProcessBuilder("fpack", "-r", "-Y", "-S", reference.toString())
                .redirectOutput(packed.toFile()).start();
        assertEquals(0, p.waitFor(), new String(p.getErrorStream().readAllBytes()));
        assertEquals(1, FitsLayout.inspect(packed).compressedImageHdu());

        Path fz = dir.resolve("packed.fits.fz");
        RiceArchiver.Result result = archiver.compress(packed, fz);
        assertEquals(2, result.shift());
        assertTrue(result.bytes() < Files.size(packed), "the shift gains on top of the existing Rice coding");
        ShiftedFits.Scan restored;
        try (InputStream in = archiver.openRestored(fz)) {
            restored = ShiftedFits.scanStream(in);
        }
        assertTrue(ShiftedFits.scan(reference, 0).matches(restored));
        assertNoTempFiles();
    }

    @Test
    void openImageReadsPlainAndTileCompressedFilesAlike() throws IOException {
        byte[] original = TestFits.cameraLike(128, 64, 1, 21);
        Path plain = TestFits.write(dir, "any.fits", original);
        Path fz = dir.resolve("any.fits.fz");
        archiver.compress(plain, fz);
        ShiftedFits.Scan fromPlain;
        ShiftedFits.Scan fromFz;
        try (InputStream in = archiver.openImage(plain)) {
            fromPlain = ShiftedFits.scanStream(in);
        }
        try (InputStream in = archiver.openImage(fz)) {
            fromFz = ShiftedFits.scanStream(in);
        }
        assertTrue(ShiftedFits.scan(plain, 0).matches(fromPlain));
        assertTrue(fromPlain.matches(fromFz));
        assertThrows(IOException.class, () -> archiver.openImage(TestFits.write(dir, "none.fits",
                FitsHeader.of(List.of(FitsHeader.formatCard("SIMPLE", "T", null),
                        FitsHeader.formatCard("BITPIX", "8", null),
                        FitsHeader.formatCard("NAXIS", "0", null))).toBytes())));
    }

    @Test
    void refusesInputWithoutAnImage() throws IOException {
        FitsHeader header = FitsHeader.of(List.of(
                FitsHeader.formatCard("SIMPLE", "T", null),
                FitsHeader.formatCard("BITPIX", "8", null),
                FitsHeader.formatCard("NAXIS", "0", null)));
        Path empty = TestFits.write(dir, "empty.fits", header.toBytes());
        Path fz = dir.resolve("empty.fits.fz");
        assertThrows(IOException.class, () -> archiver.compress(empty, fz));
        assertFalse(Files.exists(fz));
        assertNoTempFiles();
    }

    @Test
    void garbageInputFailsCleanly() throws IOException {
        Path junk = TestFits.write(dir, "junk.fits", "this is not a FITS file".repeat(200).getBytes());
        Path fz = dir.resolve("junk.fits.fz");
        assertThrows(IOException.class, () -> archiver.compress(junk, fz));
        assertFalse(Files.exists(fz));
        assertNoTempFiles();
    }

    /**
     * Real frames: run with -Dcamserver.test.sampleDir=/dir/with/frames to exercise the pipeline
     * on actual camera files (plain and astropy-Rice). Skipped otherwise.
     */
    @Test
    void realSampleFrames() throws IOException {
        String sampleDir = System.getProperty("camserver.test.sampleDir");
        Assumptions.assumeTrue(sampleDir != null && !sampleDir.isBlank(), "no sample dir configured");
        List<Path> samples = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(sampleDir), "*.fits")) {
            stream.forEach(samples::add);
        }
        Assumptions.assumeTrue(!samples.isEmpty(), "no *.fits in " + sampleDir);
        for (Path sample : samples) {
            Path source = Files.copy(sample, dir.resolve(sample.getFileName()));
            Path fz = dir.resolve(sample.getFileName() + ".fz");
            long started = System.nanoTime();
            RiceArchiver.Result result = archiver.compress(source, fz);
            long millis = (System.nanoTime() - started) / 1_000_000;

            // Reference: the plain pixels of the source, decoded by imcopy when it is tile-compressed.
            FitsLayout layout = FitsLayout.inspect(source);
            ShiftedFits.Scan reference;
            if (layout.plainImageHdu() == 0) {
                reference = ShiftedFits.scan(source, 0);
            } else {
                try (InputStream in = new RiceArchiver("fpack", "imcopy", 0, Duration.ofMinutes(2), dir.resolve("tmp2"))
                        .openRestored(source)) {
                    reference = ShiftedFits.scanStream(in);
                }
            }
            ShiftedFits.Scan restored;
            try (InputStream in = archiver.openRestored(fz)) {
                restored = ShiftedFits.scanStream(in);
            }
            System.out.printf("%s: %,d -> %,d bytes (%.1f%%), shift %d, %d ms, identical=%s%n",
                    sample.getFileName(), Files.size(source), result.bytes(),
                    100.0 * result.bytes() / Files.size(source), result.shift(), millis, reference.matches(restored));
            assertTrue(reference.matches(restored), sample.getFileName() + " did not round-trip");
            String outDir = System.getProperty("camserver.test.outDir");
            if (outDir != null && !outDir.isBlank()) {
                Files.copy(fz, Files.createDirectories(Path.of(outDir)).resolve(fz.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.delete(source);
            Files.delete(fz);
        }
        assertNoTempFiles();
    }

    private void assertNoTempFiles() throws IOException {
        List<String> leftovers = new ArrayList<>();
        for (Path d : List.of(dir, dir.resolve("tmp"))) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(d, "*.tmp-*")) {
                stream.forEach(p -> leftovers.add(p.getFileName().toString()));
            }
        }
        assertTrue(leftovers.isEmpty(), "temp files left behind: " + leftovers);
    }
}
