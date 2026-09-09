package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.archive.ArchiveFileInfo;
import edu.camserver.app.model.archive.ArchiveFileResult;
import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import edu.camserver.app.model.archive.ArchiveStats;
import edu.camserver.app.model.archive.StoredImage;
import edu.camserver.app.service.fits.RiceArchiver;
import edu.camserver.app.service.fits.ShiftedFits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end archive behaviour with the Rice format. Needs fpack and imcopy; skipped otherwise. */
class ImageArchiveServiceRiceTest {

    @TempDir
    Path root;
    Path images;
    ImageArchiveService service;

    @BeforeEach
    void setUp() throws IOException {
        images = Files.createDirectories(root.resolve("images"));
        service = newService("rice");
        RiceArchiver.Availability availability = service.riceAvailability();
        Assumptions.assumeTrue(availability.available(), "fpack/imcopy not installed: " + availability.detail());
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private ImageArchiveService newService(String format) {
        return new ImageArchiveService(new ImagePaths(images.toString()), 6, 2, 0, ".fits,.fit,.fts",
                root.resolve("archive-tmp").toString(), 60, format, "fpack", "imcopy", 2, 120);
    }

    private static void gzip(Path target, byte[] content) throws IOException {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            out.write(content);
        }
    }

    private static Path staleFile(Path path, Instant writtenAt) throws IOException {
        Files.write(path, "leftover".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, FileTime.from(writtenAt));
        return path;
    }

    private static byte[] frame(long seed) {
        Random random = new Random(seed);
        int width = 240, height = 80, planes = 3;
        byte[] header = headerBytes(width, height, planes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header);
        int count = width * height * planes;
        for (int i = 0; i < count; i++) {
            int unsigned = ((1200 + random.nextInt(300)) & 0x3fff) << 2;
            short stored = (short) (unsigned - 32768);
            out.write(stored >> 8);
            out.write(stored);
        }
        long dataBytes = (long) count * 2;
        long padding = ((dataBytes + 2879) / 2880) * 2880 - dataBytes;
        for (long i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] headerBytes(int width, int height, int planes) {
        StringBuilder sb = new StringBuilder();
        for (String card : new String[] {
                "SIMPLE  =                    T / conforms to FITS standard",
                "BITPIX  =                   16 / array data type",
                "NAXIS   =                    3 / number of array dimensions",
                "NAXIS1  = " + String.format("%20d", width),
                "NAXIS2  = " + String.format("%20d", height),
                "NAXIS3  = " + String.format("%20d", planes),
                "EXTEND  =                    T",
                "BSCALE  =                    1",
                "BZERO   =                32768",
                "END"}) {
            sb.append(String.format("%-80s", card));
        }
        while (sb.length() % 2880 != 0) {
            sb.append(' ');
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static ShiftedFits.Scan scanOf(byte[] bytes) throws IOException {
        return ShiftedFits.scanStream(new ByteArrayInputStream(bytes));
    }

    @Test
    void compressLocateServeAndDecompress() throws IOException {
        String name = "QHY5III678C-test_2026-01-05T01:02:03.456.fits";
        byte[] original = frame(1);
        Files.write(images.resolve(name), original);

        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed(), result.message());
        assertTrue(result.message().contains("2-bit shift"), result.message());
        assertTrue(result.bytesAfter() < result.bytesBefore());
        assertFalse(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".fz")));

        StoredImage stored = service.locate(name).orElseThrow();
        assertEquals(StoredImage.Format.RICE, stored.format());
        assertTrue(stored.compressed());
        assertFalse(stored.gzipped());
        assertEquals(name, stored.logicalName());
        assertEquals(StoredImage.Format.RICE, service.locate(name + ".fz").orElseThrow().format());
        assertEquals(StoredImage.Format.RICE, service.locate(name + ".gz").orElseThrow().format(),
                "a .gz request still finds the frame in its stored form");

        ArchiveFileInfo info = service.describe(name);
        assertEquals("rice", info.format());
        assertEquals(name + ".fz", info.storedAs());
        assertFalse(info.gzipped());

        try (InputStream in = service.openDecompressed(stored)) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())), "served plain frame is pixel-identical");
        }

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        service.writeGzipped(stored, gz);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz.toByteArray()))) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())), "on-the-fly gzip is pixel-identical");
        }

        Path materialized = service.materialize(stored);
        assertTrue(scanOf(original).matches(ShiftedFits.scan(materialized, 0)));
        assertEquals(materialized, service.materialize(stored), "materialised copy is reused");

        assertEquals("already compressed", service.compress(name, false).message());

        ArchiveFileResult back = service.decompress(name, false);
        assertTrue(back.changed());
        assertFalse(Files.exists(images.resolve(name + ".fz")));
        assertTrue(scanOf(original).matches(ShiftedFits.scan(images.resolve(name), 0)));
        assertEquals("already plain", service.decompress(name, false).message());
    }

    @Test
    void gzipArchivesAreConvertedByCompressJobs() throws Exception {
        byte[] plainFrame = frame(2);
        byte[] gzFrame = frame(3);
        String plainName = "cam_2026-02-01T00:00:00.000.fits";
        String gzName = "cam_2026-02-02T00:00:00.000.fits";
        Files.write(images.resolve(plainName), plainFrame);
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(images.resolve(gzName + ".gz")))) {
            out.write(gzFrame);
        }
        Files.write(images.resolve("empty_2026-02-03T00:00:00.000.fits"), new byte[0]);
        Files.write(images.resolve("note.txt"), "not a frame".getBytes(StandardCharsets.UTF_8));

        ArchiveSelection selection = new ArchiveSelection();
        selection.setAll(true);
        ArchiveJob job = service.startJob(ArchiveJob.Type.COMPRESS, selection);
        waitFor(job);

        assertEquals(ArchiveJob.State.COMPLETED, job.getState(), job.getMessage());
        assertEquals(2, job.getCandidates(), "the empty file and the text file are not candidates");
        assertEquals(2, job.getSucceeded());
        assertEquals(0, job.getFailed(), String.join("; ", job.getErrors()));
        assertTrue(Files.exists(images.resolve(plainName + ".fz")));
        assertTrue(Files.exists(images.resolve(gzName + ".fz")));
        assertFalse(Files.exists(images.resolve(plainName)));
        assertFalse(Files.exists(images.resolve(gzName + ".gz")));

        try (InputStream in = service.openDecompressed(service.locate(gzName).orElseThrow())) {
            assertTrue(scanOf(gzFrame).matches(scanOf(in.readAllBytes())));
        }

        ArchiveStats stats = service.stats(true);
        assertEquals(2, stats.fitsRiceFiles());
        assertEquals(0, stats.fitsGzipFiles());
        assertEquals(1, stats.fitsPlainFiles(), "the empty .fits still counts as plain");
        assertEquals(1, stats.emptyFiles());

        // a decompress job takes both back to plain
        ArchiveJob undo = service.startJob(ArchiveJob.Type.DECOMPRESS, selection);
        waitFor(undo);
        assertEquals(2, undo.getSucceeded(), undo.getMessage());
        assertTrue(scanOf(gzFrame).matches(ShiftedFits.scan(images.resolve(gzName), 0)));
        assertTrue(scanOf(plainFrame).matches(ShiftedFits.scan(images.resolve(plainName), 0)));
    }

    @Test
    void conflictingFormsThatDifferAreLeftAlone() throws IOException {
        String name = "dup_2026-03-01T00:00:00.000.fits";
        Files.write(images.resolve(name), frame(4));
        gzip(images.resolve(name + ".gz"), frame(9));
        ArchiveFileResult result = service.compress(name, false);
        assertFalse(result.changed());
        assertTrue(result.message().contains("both plain and .gz exist but hold different data"), result.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".gz")));
        assertFalse(Files.exists(images.resolve(name + ".fz")));
    }

    @Test
    void redundantCopiesNextToAnArchiveAreRemoved() throws IOException {
        String name = "twice_2026-03-02T00:00:00.000.fits";
        byte[] original = frame(10);
        Files.write(images.resolve(name), original);
        assertTrue(service.compress(name, false).changed());
        long fzSize = Files.size(images.resolve(name + ".fz"));

        // a run interrupted after writing the .fz leaves the source behind; the next run drops it
        Files.write(images.resolve(name), original);
        ArchiveFileResult dry = service.compress(name, true);
        assertTrue(dry.changed());
        assertTrue(dry.message().startsWith("dry run (would remove the redundant plain copy"), dry.message());
        assertTrue(Files.exists(images.resolve(name)), "a dry run deletes nothing");

        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed(), result.message());
        assertEquals("removed redundant plain copy (identical to .fz)", result.message());
        assertEquals(original.length + fzSize, result.bytesBefore());
        assertEquals(fzSize, result.bytesAfter());
        assertFalse(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".fz")));

        // the same for an old whole-file gzip next to the .fz
        gzip(images.resolve(name + ".gz"), original);
        result = service.compress(name, false);
        assertTrue(result.changed(), result.message());
        assertEquals("removed redundant .gz copy (identical to .fz)", result.message());
        assertFalse(Files.exists(images.resolve(name + ".gz")));
        assertEquals("already compressed", service.compress(name, false).message());

        try (InputStream in = service.openDecompressed(service.locate(name).orElseThrow())) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())), "the kept .fz still serves the frame");
        }
    }

    @Test
    void copiesThatDifferFromTheArchiveAreKept() throws IOException {
        String name = "differs_2026-03-03T00:00:00.000.fits";
        Files.write(images.resolve(name), frame(11));
        assertTrue(service.compress(name, false).changed());
        Files.write(images.resolve(name), frame(12));
        ArchiveFileResult result = service.compress(name, false);
        assertFalse(result.changed());
        assertTrue(result.message().contains("both plain and .fz exist but hold different data"), result.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".fz")));
    }

    @Test
    void plainNextToAnIdenticalGzipIsDroppedBeforeConversion() throws IOException {
        String name = "pair_2026-03-04T00:00:00.000.fits";
        byte[] original = frame(13);
        Files.write(images.resolve(name), original);
        gzip(images.resolve(name + ".gz"), original);
        long gzSize = Files.size(images.resolve(name + ".gz"));

        ArchiveFileResult dry = service.compress(name, true);
        assertTrue(dry.changed());
        assertTrue(dry.message().contains("then convert .gz to .fz"), dry.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertTrue(Files.exists(images.resolve(name + ".gz")));

        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed(), result.message());
        assertTrue(result.message().startsWith("removed redundant plain copy (identical to .gz); converted .gz to .fz"),
                result.message());
        assertEquals(original.length + gzSize, result.bytesBefore());
        assertFalse(Files.exists(images.resolve(name)));
        assertFalse(Files.exists(images.resolve(name + ".gz")));
        assertTrue(Files.exists(images.resolve(name + ".fz")));
        assertEquals(Files.size(images.resolve(name + ".fz")), result.bytesAfter());
        try (InputStream in = service.openDecompressed(service.locate(name).orElseThrow())) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())));
        }
    }

    @Test
    void decompressDropsAnArchiveIdenticalToThePlainFile() throws IOException {
        String name = "restored_2026-03-05T00:00:00.000.fits";
        byte[] original = frame(14);
        Files.write(images.resolve(name), original);
        assertTrue(service.compress(name, false).changed());
        // a decompress interrupted after restoring the plain file leaves the .fz behind
        Files.write(images.resolve(name), original);
        ArchiveFileResult result = service.decompress(name, false);
        assertTrue(result.changed(), result.message());
        assertEquals("removed redundant .fz copy (identical to plain)", result.message());
        assertFalse(Files.exists(images.resolve(name + ".fz")));
        assertTrue(scanOf(original).matches(ShiftedFits.scan(images.resolve(name), 0)));
    }

    @Test
    void staleTempFilesAreSweptAtStartupAndBeforeJobs() throws Exception {
        Path tmp = Files.createDirectories(root.resolve("archive-tmp"));
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        List<Path> stale = List.of(
                staleFile(images.resolve("a_2026-01-01T00:00:00.000.fits.gz.tmp-123"), longAgo),
                staleFile(images.resolve("a_2026-01-01T00:00:00.000.fits.fz.tmp-124"), longAgo),
                staleFile(images.resolve("a_2026-01-01T00:00:00.000.fits.tmp-125"), longAgo),
                staleFile(tmp.resolve("a_2026-01-01T00:00:00.000.fits.gunzip.tmp-126"), longAgo),
                staleFile(tmp.resolve("a_2026-01-01T00:00:00.000.fits.shift.tmp-127"), longAgo));
        List<Path> kept = List.of(
                staleFile(images.resolve("notes.txt.tmp-1"), longAgo),                        // not an archive temp file
                staleFile(tmp.resolve("a_2026-01-01T00:00:00.000.fits"), longAgo),            // materialised copy, kept for reuse
                staleFile(images.resolve("b_2026-01-01T00:00:00.000.fits.fz.tmp-9"),          // written after start, still young
                        Instant.now().plus(Duration.ofMinutes(1))));

        ImageArchiveService.SweepResult sweep = service.sweepStaleTempFiles();
        assertEquals(5, sweep.deleted(), sweep.toString());
        assertTrue(sweep.problems().isEmpty());
        for (Path path : stale) {
            assertFalse(Files.exists(path), path.getFileName().toString());
        }
        for (Path path : kept) {
            assertTrue(Files.exists(path), path.getFileName().toString());
        }
        assertEquals(sweep, service.config().get("lastSweep"));

        // whatever was written before the process started is stale however young it is
        Path fromPreviousRun = kept.get(2);
        Files.setLastModifiedTime(fromPreviousRun, FileTime.from(Instant.now().minusSeconds(5)));
        service.shutdown();
        service = newService("rice");
        assertEquals(1, service.sweepStaleTempFiles().deleted());
        assertFalse(Files.exists(fromPreviousRun));

        // a dry-run job touches nothing; a real one sweeps before it starts
        Path leftover = staleFile(images.resolve("c_2026-01-01T00:00:00.000.fits.fz.tmp-5"), longAgo);
        ArchiveSelection selection = new ArchiveSelection();
        selection.setAll(true);
        selection.setDryRun(true);
        waitFor(service.startJob(ArchiveJob.Type.COMPRESS, selection));
        assertTrue(Files.exists(leftover));
        selection.setDryRun(false);
        waitFor(service.startJob(ArchiveJob.Type.COMPRESS, selection));
        assertFalse(Files.exists(leftover));
        assertEquals(1, service.lastSweep().orElseThrow().deleted());
    }

    @Test
    void dryRunTouchesNothing() throws IOException {
        String name = "dry_2026-03-01T00:00:00.000.fits";
        Files.write(images.resolve(name), frame(5));
        ArchiveFileResult result = service.compress(name, true);
        assertTrue(result.changed());
        assertEquals("dry run", result.message());
        assertTrue(Files.exists(images.resolve(name)));
        assertFalse(Files.exists(images.resolve(name + ".fz")));
    }

    @Test
    void gzipFormatStillWorks() throws IOException {
        service.shutdown();
        service = newService("gzip");
        String name = "old_2026-03-01T00:00:00.000.fits";
        byte[] original = frame(6);
        Files.write(images.resolve(name), original);
        ArchiveFileResult result = service.compress(name, false);
        assertTrue(result.changed());
        assertTrue(Files.exists(images.resolve(name + ".gz")));
        StoredImage stored = service.locate(name).orElseThrow();
        assertEquals(StoredImage.Format.GZIP, stored.format());
        assertTrue(stored.gzipped());
        try (InputStream in = service.openDecompressed(stored)) {
            assertTrue(scanOf(original).matches(scanOf(in.readAllBytes())));
        }
        assertEquals("gzip", service.config().get("format"));
    }

    private static void waitFor(ArchiveJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (job.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(job.isActive(), "job did not finish: " + job.getState());
    }

    @SuppressWarnings("unused")
    private static List<String> names(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        return names;
    }
}
