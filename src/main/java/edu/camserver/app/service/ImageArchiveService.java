package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.archive.ArchiveFileInfo;
import edu.camserver.app.model.archive.ArchiveFileResult;
import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import edu.camserver.app.model.archive.ArchiveStats;
import edu.camserver.app.model.archive.StoredImage;
import edu.camserver.app.model.archive.StoredImage.Format;
import edu.camserver.app.service.fits.RiceArchiver;
import edu.camserver.app.service.fits.ShiftedFits;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-demand compression archive for the flat image directory.
 *
 * <p>A frame is stored as {@code name.fits}, {@code name.fits.gz} (whole-file gzip) or
 * {@code name.fits.fz} (FITS tile compression with Rice, see {@link RiceArchiver}); never more
 * than one for long. {@link #locate} hides that from callers; {@link #compress}/{@link #decompress}
 * switch a single file between plain and the configured archive format; {@link #startJob} does
 * the same for a whole selection in the background. Compressed output is verified (decoded and
 * compared with the source) before the original is removed. With the Rice format, existing
 * .gz files are converted as well.
 *
 * <p>Interrupted runs clean up after themselves on the next one: a source left next to its
 * verified archive is removed once the archive is shown to hold the same frame
 * ({@link #compress}), and partially written temp files are swept away
 * ({@link #sweepStaleTempFiles}).
 */
@Service
public class ImageArchiveService {
    private static final Logger log = LoggerFactory.getLogger(ImageArchiveService.class);
    private static final String GZ = Format.GZIP.suffix();
    private static final String FZ = Format.RICE.suffix();
    private static final int BUFFER = 256 * 1024;
    private static final int JOB_HISTORY = 20;
    private static final Duration STATS_TTL = Duration.ofMinutes(5);
    private static final Pattern FILE_TIMESTAMP =
            Pattern.compile("_(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?)\\.[A-Za-z0-9]+(?:\\.gz|\\.fz)?$");
    private static final Set<String> JPG_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png");
    /** Every temp file this service writes: the eventual name plus {@code .tmp-<nanos>}. */
    private static final Pattern TEMP_NAME = Pattern.compile("^(.+)\\.tmp-\\d+$");

    /** What compress jobs produce. */
    public enum ArchiveFormat { GZIP, RICE }

    private final ImagePaths imagePaths;
    private final int gzipLevel;
    private final int minAgeMinutes;
    private final List<String> defaultExtensions;
    private final Path tempDir;
    private final Duration staleTempAge;
    private final Instant startedAt = Instant.now();
    private volatile SweepResult lastSweep;
    private final int workerThreads;
    private final ArchiveFormat format;
    private final RiceArchiver rice;
    private volatile RiceArchiver.Availability riceAvailability;
    private final ExecutorService jobRunner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "image-archive-job");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers;
    private final AtomicReference<ArchiveJob> activeJob = new AtomicReference<>();
    private final ConcurrentLinkedDeque<ArchiveJob> jobs = new ConcurrentLinkedDeque<>();
    private volatile ArchiveStats cachedStats;
    private final Object statsLock = new Object();

    public ImageArchiveService(
            ImagePaths imagePaths,
            @Value("${app.images.archive.gzip-level:6}") int gzipLevel,
            @Value("${app.images.archive.worker-threads:4}") int workerThreads,
            @Value("${app.images.archive.min-age-minutes:10}") int minAgeMinutes,
            @Value("${app.images.archive.extensions:.fits,.fit,.fts}") String extensions,
            @Value("${app.images.archive.temp-dir:}") String tempDir,
            @Value("${app.images.archive.stale-temp-minutes:60}") int staleTempMinutes,
            @Value("${app.images.archive.format:rice}") String format,
            @Value("${app.images.archive.rice.fpack-command:fpack}") String fpackCommand,
            @Value("${app.images.archive.rice.imcopy-command:imcopy}") String imcopyCommand,
            @Value("${app.images.archive.rice.shift-bits:2}") int riceShiftBits,
            @Value("${app.images.archive.rice.tool-timeout-seconds:300}") int riceToolTimeoutSeconds) {
        this.imagePaths = imagePaths;
        this.gzipLevel = Math.max(1, Math.min(9, gzipLevel));
        this.minAgeMinutes = Math.max(0, minAgeMinutes);
        this.defaultExtensions = normalizeExtensions(Arrays.asList(extensions.split(",")));
        this.tempDir = tempDir == null || tempDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "camserver-archive")
                : Path.of(tempDir);
        this.staleTempAge = Duration.ofMinutes(Math.max(0, staleTempMinutes));
        this.format = parseFormat(format);
        this.rice = new RiceArchiver(fpackCommand, imcopyCommand, riceShiftBits,
                Duration.ofSeconds(Math.max(1, riceToolTimeoutSeconds)), this.tempDir);
        this.workerThreads = Math.max(1, workerThreads);
        this.workers = Executors.newFixedThreadPool(this.workerThreads, r -> {
            Thread t = new Thread(r, "image-archive-worker");
            t.setDaemon(true);
            return t;
        });
    }

    private static ArchiveFormat parseFormat(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "rice", "fz", "fpack" -> ArchiveFormat.RICE;
            case "gzip", "gz" -> ArchiveFormat.GZIP;
            default -> throw new IllegalArgumentException("Unknown app.images.archive.format: " + value + " (use rice or gzip)");
        };
    }

    @PostConstruct
    void logConfiguration() {
        if (format == ArchiveFormat.RICE) {
            RiceArchiver.Availability availability = riceAvailability();
            if (availability.available()) {
                log.info("Image archive format: rice ({}; shift {} bit(s); temp dir {})",
                        availability.detail(), rice.shiftBits(), tempDir);
            } else {
                log.warn("Image archive format is rice but the CFITSIO tools are missing ({}). "
                        + "Compression jobs will not run until they are installed (apt install libcfitsio-bin).",
                        availability.detail());
            }
        } else {
            log.info("Image archive format: gzip (level {})", gzipLevel);
        }
    }

    /** Leftovers of the previous process cannot be in use any more: clear them before the first job. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        jobRunner.submit(() -> {
            try {
                sweepStaleTempFiles();
            } catch (RuntimeException e) {
                log.warn("Startup sweep of stale temp files failed", e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        ArchiveJob job = activeJob.get();
        if (job != null) {
            job.requestCancel();
        }
        jobRunner.shutdownNow();
        workers.shutdownNow();
    }

    // ---------------------------------------------------------------- config

    public ArchiveFormat format() {
        return format;
    }

    /** Result of the last tool probe, probing on first use. */
    public RiceArchiver.Availability riceAvailability() {
        RiceArchiver.Availability current = riceAvailability;
        if (current == null) {
            current = rice.probe();
            riceAvailability = current;
        }
        return current;
    }

    /** Re-runs the tool probe, e.g. after installing the tools. */
    public RiceArchiver.Availability reprobeRice() {
        RiceArchiver.Availability fresh = rice.probe();
        riceAvailability = fresh;
        return fresh;
    }

    /** Why compression cannot run right now, or empty when it can. */
    public Optional<String> compressionProblem() {
        if (format == ArchiveFormat.RICE) {
            RiceArchiver.Availability availability = riceAvailability();
            if (!availability.available()) {
                return Optional.of("Rice tools missing: " + availability.detail()
                        + " (install CFITSIO's fpack and imcopy, e.g. apt install libcfitsio-bin)");
            }
        }
        return Optional.empty();
    }

    public Map<String, Object> config() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format.name().toLowerCase(Locale.ROOT));
        map.put("gzipLevel", gzipLevel);
        map.put("workerThreads", workerThreads);
        map.put("minAgeMinutes", minAgeMinutes);
        map.put("extensions", defaultExtensions);
        map.put("tempDir", tempDir.toString());
        map.put("staleTempMinutes", staleTempAge.toMinutes());
        map.put("lastSweep", lastSweep);
        Map<String, Object> riceMap = new LinkedHashMap<>();
        riceMap.put("fpackCommand", rice.fpackCommand());
        riceMap.put("imcopyCommand", rice.imcopyCommand());
        riceMap.put("shiftBits", rice.shiftBits());
        RiceArchiver.Availability availability = riceAvailability();
        riceMap.put("available", availability.available());
        riceMap.put("fpackVersion", availability.fpackVersion());
        riceMap.put("detail", availability.detail());
        map.put("rice", riceMap);
        map.put("compressionProblem", compressionProblem().orElse(null));
        return map;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Finds the on-disk form of a requested file. The request may name the plain file or a
     * compressed form; whichever exists is returned, preferring the exact form asked for.
     */
    public Optional<StoredImage> locate(String requestedName) {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        Path fz = imagePaths.resolve(logicalName + FZ);
        List<Path> order = switch (Format.ofFileName(requestedName.trim())) {
            case GZIP -> List.of(gz, plain, fz);
            case RICE -> List.of(fz, plain, gz);
            case PLAIN -> List.of(plain, fz, gz);
        };
        for (Path candidate : order) {
            if (Files.isRegularFile(candidate)) {
                try {
                    Format format = candidate == plain ? Format.PLAIN : candidate == gz ? Format.GZIP : Format.RICE;
                    return Optional.of(new StoredImage(logicalName, candidate, format, Files.size(candidate)));
                } catch (IOException e) {
                    log.warn("Cannot stat {}: {}", candidate, e.getMessage());
                }
            }
        }
        return Optional.empty();
    }

    public ArchiveFileInfo describe(String requestedName) {
        String logicalName = logicalName(requestedName);
        Optional<StoredImage> stored = locate(requestedName);
        if (stored.isEmpty()) {
            return new ArchiveFileInfo(logicalName, false, false, null, null, 0, null);
        }
        StoredImage image = stored.get();
        Instant modified = null;
        try {
            modified = Files.getLastModifiedTime(image.path()).toInstant();
        } catch (IOException ignored) {
            // informational only
        }
        return new ArchiveFileInfo(logicalName, true, image.gzipped(),
                image.format().name().toLowerCase(Locale.ROOT),
                image.path().getFileName().toString(), image.size(), modified);
    }

    /** Opens the file for reading with any compression layer removed. */
    public InputStream openDecompressed(StoredImage stored) throws IOException {
        return switch (stored.format()) {
            case PLAIN -> new BufferedInputStream(Files.newInputStream(stored.path()), BUFFER);
            case GZIP -> new GZIPInputStream(new BufferedInputStream(Files.newInputStream(stored.path()), BUFFER), BUFFER);
            case RICE -> rice.openRestored(stored.path());
        };
    }

    /** Streams the file gzip-compressed, compressing on the fly unless it is stored as gzip. */
    public void writeGzipped(StoredImage stored, OutputStream out) throws IOException {
        if (stored.gzipped()) {
            Files.copy(stored.path(), out);
            return;
        }
        try (InputStream in = openDecompressed(stored);
             GZIPOutputStream gzOut = new LevelGzipOutputStream(out, gzipLevel)) {
            in.transferTo(gzOut);
        }
    }

    /**
     * Returns a path that external tools can read directly. Plain files are returned as-is;
     * compressed files are expanded into the temp directory (reused while still current).
     */
    public Path materialize(StoredImage stored) throws IOException {
        if (!stored.compressed()) {
            return stored.path();
        }
        Files.createDirectories(tempDir);
        Path target = tempDir.resolve(stored.logicalName());
        FileTime sourceTime = Files.getLastModifiedTime(stored.path());
        if (Files.isRegularFile(target) && Files.getLastModifiedTime(target).compareTo(sourceTime) >= 0) {
            return target;
        }
        Path tmp = tempDir.resolve(stored.logicalName() + ".tmp-" + System.nanoTime());
        try (InputStream in = openDecompressed(stored);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
            in.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.setLastModifiedTime(tmp, sourceTime);
        moveReplacing(tmp, target);
        return target;
    }

    public MediaType mediaTypeFor(String logicalName) {
        String ext = extensionOf(logicalName);
        return switch (ext) {
            case ".fits", ".fit", ".fts" -> MediaType.parseMediaType("application/fits");
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG;
            case ".png" -> MediaType.IMAGE_PNG;
            case ".csv" -> MediaType.parseMediaType("text/csv");
            case ".json" -> MediaType.APPLICATION_JSON;
            case ".txt", ".log" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    // ------------------------------------------------------------ single files

    public ArchiveFileResult compress(String requestedName, boolean dryRun) throws IOException {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        Path fz = imagePaths.resolve(logicalName + FZ);
        boolean hasPlain = Files.isRegularFile(plain);
        boolean hasGz = Files.isRegularFile(gz);
        boolean hasFz = Files.isRegularFile(fz);

        if (format == ArchiveFormat.GZIP) {
            if (!hasPlain) {
                if (hasGz || hasFz) {
                    return ArchiveFileResult.skipped(logicalName, "compress", Files.size(hasGz ? gz : fz), "already compressed");
                }
                throw new FileNotFoundException(logicalName);
            }
            if (hasGz || hasFz) {
                // an earlier run wrote the archive but did not get to remove the source
                return removeRedundant(logicalName, "compress", plain, hasGz ? gz : fz, dryRun, "");
            }
            long size = Files.size(plain);
            if (size == 0) {
                return ArchiveFileResult.skipped(logicalName, "compress", 0, "empty file");
            }
            if (isTooRecent(plain)) {
                return ArchiveFileResult.skipped(logicalName, "compress", size,
                        "modified less than " + minAgeMinutes + " minutes ago");
            }
            if (dryRun) {
                return new ArchiveFileResult(logicalName, "compress", true, size, size, "dry run");
            }
            long after = compressGzip(plain, gz, size);
            return new ArchiveFileResult(logicalName, "compress", true, size, after, "compressed");
        }

        // Rice: plain files and old .gz files both become .fz
        if (hasFz) {
            if (hasPlain || hasGz) {
                // an earlier run wrote the .fz but did not get to remove the source
                return removeRedundant(logicalName, "compress", hasPlain ? plain : gz, fz, dryRun, "");
            }
            return ArchiveFileResult.skipped(logicalName, "compress", Files.size(fz), "already compressed");
        }
        if (!hasPlain && !hasGz) {
            throw new FileNotFoundException(logicalName);
        }
        long removedBytes = 0;
        String removedNote = "";
        if (hasPlain && hasGz) {
            // Both forms: the plain file is redundant once the .gz proves to hold the same bytes,
            // and the .gz is then converted like any other.
            if (isTooRecent(plain) || isTooRecent(gz)) {
                return ArchiveFileResult.skipped(logicalName, "compress", Files.size(plain),
                        "modified less than " + minAgeMinutes + " minutes ago");
            }
            if (!dryRun) {
                Optional<String> problem = compressionProblem();
                if (problem.isPresent()) {
                    throw new IOException(problem.get());
                }
            }
            ArchiveFileResult dropped = removeRedundant(logicalName, "compress", plain, gz, dryRun,
                    ", then convert .gz to .fz");
            if (!dropped.changed() || dryRun) {
                return dropped;
            }
            hasPlain = false;
            removedBytes = dropped.bytesBefore() - dropped.bytesAfter();
            removedNote = dropped.message() + "; ";
        }
        Path source = hasPlain ? plain : gz;
        long size = Files.size(source);
        if (size == 0) {
            return ArchiveFileResult.skipped(logicalName, "compress", 0, "empty file");
        }
        if (isTooRecent(source)) {
            return ArchiveFileResult.skipped(logicalName, "compress", size,
                    "modified less than " + minAgeMinutes + " minutes ago");
        }
        if (dryRun) {
            return new ArchiveFileResult(logicalName, "compress", true, size, size,
                    hasPlain ? "dry run" : "dry run (would convert .gz to .fz)");
        }
        Optional<String> problem = compressionProblem();
        if (problem.isPresent()) {
            throw new IOException(problem.get());
        }
        RiceArchiver.Result result = compressRice(logicalName, source, fz);
        String message = (hasPlain ? "compressed" : "converted .gz to .fz")
                + (result.shift() > 0 ? " (rice, " + result.shift() + "-bit shift)" : " (rice)");
        return new ArchiveFileResult(logicalName, "compress", true, size + removedBytes, result.bytes(),
                removedNote + message);
    }

    public ArchiveFileResult decompress(String requestedName, boolean dryRun) throws IOException {
        String logicalName = logicalName(requestedName);
        Path plain = imagePaths.resolve(logicalName);
        Path gz = imagePaths.resolve(logicalName + GZ);
        Path fz = imagePaths.resolve(logicalName + FZ);
        Path source = Files.isRegularFile(fz) ? fz : Files.isRegularFile(gz) ? gz : null;
        if (source == null) {
            if (Files.isRegularFile(plain)) {
                return ArchiveFileResult.skipped(logicalName, "decompress", Files.size(plain), "already plain");
            }
            throw new FileNotFoundException(logicalName);
        }
        long size = Files.size(source);
        if (Files.isRegularFile(plain)) {
            // an earlier run restored the plain file but did not get to remove the archive
            return removeRedundant(logicalName, "decompress", source, plain, dryRun, "");
        }
        if (dryRun) {
            return new ArchiveFileResult(logicalName, "decompress", true, size, size, "dry run");
        }
        long after;
        if (source == fz) {
            Optional<String> problem = compressionProblem();
            if (format == ArchiveFormat.RICE && problem.isPresent()) {
                throw new IOException(problem.get());
            }
            after = decompressRice(fz, plain);
        } else {
            after = decompressGzip(gz, plain);
        }
        return new ArchiveFileResult(logicalName, "decompress", true, size, after, "decompressed");
    }

    private long compressGzip(Path plain, Path gz, long size) throws IOException {
        Path tmp = plain.resolveSibling(gz.getFileName() + ".tmp-" + System.nanoTime());
        CRC32 sourceCrc = new CRC32();
        try {
            try (InputStream in = new CheckedInputStream(
                         new BufferedInputStream(Files.newInputStream(plain), BUFFER), sourceCrc);
                 OutputStream out = new LevelGzipOutputStream(new BufferedOutputStream(
                         Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER),
                         gzipLevel)) {
                in.transferTo(out);
            }

            CRC32 roundTripCrc = new CRC32();
            long roundTripLength;
            try (InputStream in = new CheckedInputStream(
                    new GZIPInputStream(new BufferedInputStream(Files.newInputStream(tmp), BUFFER), BUFFER),
                    roundTripCrc)) {
                roundTripLength = in.transferTo(OutputStream.nullOutputStream());
            }
            if (roundTripLength != size || roundTripCrc.getValue() != sourceCrc.getValue()) {
                throw new IOException("gzip verification failed for " + plain.getFileName()
                        + " (length " + roundTripLength + "/" + size + ")");
            }

            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(plain));
            moveReplacing(tmp, gz);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.delete(plain);
        return Files.size(gz);
    }

    /**
     * Rice-compresses a plain or gzip-stored frame into {@code fz}. The source keeps its place
     * until the verified result exists, then it is deleted.
     */
    private RiceArchiver.Result compressRice(String logicalName, Path source, Path fz) throws IOException {
        Path unpacked = null;
        try {
            Path input = source;
            if (Format.ofFileName(source.getFileName().toString()) == Format.GZIP) {
                unpacked = gunzipToTemp(logicalName, source);
                input = unpacked;
            }
            RiceArchiver.Result result = rice.compress(input, fz);
            Files.setLastModifiedTime(fz, Files.getLastModifiedTime(source));
            Files.delete(source);
            return result;
        } finally {
            if (unpacked != null) {
                Files.deleteIfExists(unpacked);
            }
        }
    }

    private long decompressRice(Path fz, Path plain) throws IOException {
        long size = rice.decompress(fz, plain);
        Files.delete(fz);
        return size;
    }

    private long decompressGzip(Path gz, Path plain) throws IOException {
        Path tmp = plain.resolveSibling(plain.getFileName() + ".tmp-" + System.nanoTime());
        try {
            // GZIPInputStream checks the trailer CRC32 and length, so a clean read is a verified read.
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gz), BUFFER), BUFFER);
                 OutputStream out = new BufferedOutputStream(
                         Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
                in.transferTo(out);
            }
            Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(gz));
            moveReplacing(tmp, plain);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.delete(gz);
        return Files.size(plain);
    }

    /** Expands a .gz into the temp directory; the caller deletes the copy. */
    private Path gunzipToTemp(String logicalName, Path gz) throws IOException {
        Files.createDirectories(tempDir);
        Path unpacked = tempDir.resolve(logicalName + ".gunzip.tmp-" + System.nanoTime());
        // GZIPInputStream checks the trailer CRC32 and length, so a clean read is a verified read.
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(gz), BUFFER), BUFFER);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(unpacked, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), BUFFER)) {
            in.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(unpacked);
            throw e;
        }
        return unpacked;
    }

    // ------------------------------------------------------- redundant copies

    /**
     * Deletes {@code redundant} when {@code keep} holds the same frame. Two forms of one frame
     * exist when a run was interrupted between writing the new form and removing the old one,
     * or when a frame was uploaded again next to its archive; either way the form the current
     * operation would produce is the one to keep. Copies that differ are both left alone.
     */
    private ArchiveFileResult removeRedundant(String logicalName, String action, Path redundant, Path keep,
                                              boolean dryRun, String andThen) throws IOException {
        String redundantForm = formLabel(redundant);
        String keepForm = formLabel(keep);
        long redundantSize = Files.size(redundant);
        long keepSize = Files.size(keep);
        Optional<String> difference = compareFrames(logicalName, redundant, keep);
        if (difference.isPresent()) {
            log.warn("{}: the {} and {} forms hold different data, keeping both ({})",
                    logicalName, redundantForm, keepForm, difference.get());
            return ArchiveFileResult.skipped(logicalName, action, redundantSize + keepSize,
                    "both " + redundantForm + " and " + keepForm + " exist but hold different data; keeping both ("
                            + difference.get() + ")");
        }
        if (dryRun) {
            return new ArchiveFileResult(logicalName, action, true, redundantSize + keepSize, keepSize,
                    "dry run (would remove the redundant " + redundantForm + " copy, identical to " + keepForm
                            + andThen + ")");
        }
        Files.delete(redundant);
        log.info("{}: removed redundant {} copy ({}), identical to {}",
                logicalName, redundantForm, humanBytes(redundantSize), keepForm);
        return new ArchiveFileResult(logicalName, action, true, redundantSize + keepSize, keepSize,
                "removed redundant " + redundantForm + " copy (identical to " + keepForm + ")");
    }

    /**
     * Empty when both files hold the same frame, else what differs. Without a Rice form the two
     * must match byte for byte (a .gz is a whole-file copy); with one, the decoded images are
     * compared the same way every compression is verified.
     */
    private Optional<String> compareFrames(String logicalName, Path a, Path b) throws IOException {
        Format formA = Format.ofFileName(a.getFileName().toString());
        Format formB = Format.ofFileName(b.getFileName().toString());
        if (formA != Format.RICE && formB != Format.RICE) {
            Checksum checksumA = checksum(a, formA);
            Checksum checksumB = checksum(b, formB);
            return checksumA.equals(checksumB)
                    ? Optional.empty()
                    : Optional.of(formLabel(a) + " " + checksumA + " vs " + formLabel(b) + " " + checksumB);
        }
        ShiftedFits.Scan scanA = scanFrame(logicalName, a, formA);
        ShiftedFits.Scan scanB = scanFrame(logicalName, b, formB);
        return scanA.matches(scanB)
                ? Optional.empty()
                : Optional.of(formLabel(a) + " " + describe(scanA) + " vs " + formLabel(b) + " " + describe(scanB));
    }

    private record Checksum(long length, long crc) {
        @Override
        public String toString() {
            return length + " bytes, crc " + Long.toHexString(crc);
        }
    }

    private static Checksum checksum(Path file, Format form) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), BUFFER);
             InputStream in = new CheckedInputStream(form == Format.GZIP ? new GZIPInputStream(raw, BUFFER) : raw, crc)) {
            long length = in.transferTo(OutputStream.nullOutputStream());
            return new Checksum(length, crc.getValue());
        }
    }

    /** The decoded image of a stored frame, whatever its form. */
    private ShiftedFits.Scan scanFrame(String logicalName, Path file, Format form) throws IOException {
        Path unpacked = null;
        try {
            Path input = file;
            if (form == Format.GZIP) {
                unpacked = gunzipToTemp(logicalName, file);
                input = unpacked;
            }
            try (InputStream in = rice.openImage(input)) {
                return ShiftedFits.scanStream(in);
            }
        } finally {
            if (unpacked != null) {
                Files.deleteIfExists(unpacked);
            }
        }
    }

    private static String describe(ShiftedFits.Scan scan) {
        return "[" + scan.essentials() + ", " + scan.dataBytes() + " bytes, crc " + Long.toHexString(scan.dataCrc()) + "]";
    }

    private static String formLabel(Path file) {
        Format form = Format.ofFileName(file.getFileName().toString());
        return form == Format.PLAIN ? "plain" : form.suffix();
    }

    // ---------------------------------------------------------- temp files

    /** What a sweep of leftover temp files removed. */
    public record SweepResult(Instant at, int deleted, long bytes, List<String> problems) {
    }

    public Optional<SweepResult> lastSweep() {
        return Optional.ofNullable(lastSweep);
    }

    /**
     * Deletes temp files that an interrupted run left behind: partially written archives next to
     * the frames ({@code name.tmp-N}) and unpacked copies in the temp directory. A file written
     * before this process started belongs to a run that no longer exists; a younger one is left
     * alone until it has been idle for the configured stale age, so nothing in progress is
     * touched. Runs at startup and before every job.
     */
    public SweepResult sweepStaleTempFiles() {
        Instant now = Instant.now();
        Instant idleCutoff = now.minus(staleTempAge);
        int deleted = 0;
        long bytes = 0;
        List<String> problems = new ArrayList<>();
        for (Path dir : new LinkedHashSet<>(List.of(imagePaths.baseDir(), tempDir))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            boolean dedicated = dir.equals(tempDir) && !dir.equals(imagePaths.baseDir());
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.tmp-*")) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    if (!isArchiveTempName(name, dedicated)) {
                        continue;
                    }
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    } catch (IOException e) {
                        continue; // gone already
                    }
                    if (!attrs.isRegularFile()) {
                        continue;
                    }
                    Instant written = attrs.lastModifiedTime().toInstant();
                    if (!written.isBefore(startedAt) && !written.isBefore(idleCutoff)) {
                        continue;
                    }
                    try {
                        Files.delete(path);
                        deleted++;
                        bytes += attrs.size();
                        log.info("Removed stale temp file {} ({}, last written {})", path, humanBytes(attrs.size()), written);
                    } catch (IOException e) {
                        problems.add(name + ": " + e.getMessage());
                        log.warn("Could not remove stale temp file {}: {}", path, e.getMessage());
                    }
                }
            } catch (IOException e) {
                problems.add(dir + ": " + e.getMessage());
                log.warn("Could not scan {} for stale temp files: {}", dir, e.getMessage());
            }
        }
        SweepResult result = new SweepResult(now, deleted, bytes, List.copyOf(problems));
        lastSweep = result;
        if (deleted > 0 || !problems.isEmpty()) {
            log.info("Stale temp file sweep: removed {} file(s), {}{}", deleted, humanBytes(bytes),
                    problems.isEmpty() ? "" : ", " + problems.size() + " problem(s)");
        }
        return result;
    }

    /** Whether a name is one this service gives its temp files, so nothing else is ever touched. */
    private boolean isArchiveTempName(String name, boolean inDedicatedTempDir) {
        Matcher m = TEMP_NAME.matcher(name);
        if (!m.matches()) {
            return false;
        }
        if (inDedicatedTempDir) {
            return true;
        }
        // next to the frames: name.fits.gz.tmp-N, name.fits.fz.tmp-N or name.fits.tmp-N
        String eventual = m.group(1);
        return defaultExtensions.contains(extensionOf(Format.ofFileName(eventual).logicalName(eventual)));
    }

    // ------------------------------------------------------------------- jobs

    public ArchiveJob startJob(ArchiveJob.Type type, ArchiveSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (!selection.hasSelector()) {
            throw new IllegalArgumentException(
                    "Selection is empty: set names, prefix, olderThanDays, before/after, or all=true");
        }
        if (type == ArchiveJob.Type.COMPRESS && !selection.isDryRun()) {
            Optional<String> problem = compressionProblem();
            if (problem.isPresent()) {
                throw new IllegalStateException(problem.get());
            }
        }
        ArchiveJob job = new ArchiveJob(type, selection);
        if (!activeJob.compareAndSet(null, job)) {
            throw new IllegalStateException("An archive job is already running: " + activeJob.get().getId());
        }
        jobs.addFirst(job);
        while (jobs.size() > JOB_HISTORY) {
            jobs.pollLast();
        }
        jobRunner.submit(() -> runJob(job));
        return job;
    }

    public Optional<ArchiveJob> activeJob() {
        return Optional.ofNullable(activeJob.get());
    }

    public List<ArchiveJob> jobs() {
        return new ArrayList<>(jobs);
    }

    public Optional<ArchiveJob> job(String id) {
        return jobs.stream().filter(j -> j.getId().equals(id)).findFirst();
    }

    public boolean cancel(String id) {
        Optional<ArchiveJob> job = job(id);
        if (job.isEmpty() || !job.get().isActive()) {
            return false;
        }
        job.get().requestCancel();
        return true;
    }

    private void runJob(ArchiveJob job) {
        job.markRunning();
        try {
            if (!job.isDryRun()) {
                sweepStaleTempFiles();
            }
            List<Path> candidates = selectCandidates(job.getType(), job.getSelection());
            job.setCandidates(candidates.size());
            log.info("Archive job {} ({}, {}): {} candidate file(s){}", job.getId(), job.getType(),
                    format.name().toLowerCase(Locale.ROOT), candidates.size(), job.isDryRun() ? " [dry run]" : "");

            List<CompletableFuture<Void>> futures = new ArrayList<>(candidates.size());
            for (Path candidate : candidates) {
                futures.add(CompletableFuture.runAsync(() -> processCandidate(job, candidate), workers));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            if (job.isCancelRequested()) {
                job.finish(ArchiveJob.State.CANCELLED, "Cancelled after " + job.getProcessed() + " of "
                        + job.getCandidates() + " files");
            } else {
                job.finish(ArchiveJob.State.COMPLETED, summary(job));
            }
            log.info("Archive job {} {}: {}", job.getId(), job.getState(), job.getMessage());
            invalidateStats();
        } catch (Exception e) {
            log.error("Archive job {} failed", job.getId(), e);
            job.finish(ArchiveJob.State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            activeJob.compareAndSet(job, null);
        }
    }

    private void processCandidate(ArchiveJob job, Path candidate) {
        if (job.isCancelRequested()) {
            return;
        }
        String name = candidate.getFileName().toString();
        job.setCurrentFile(name);
        try {
            ArchiveFileResult result = job.getType() == ArchiveJob.Type.COMPRESS
                    ? compress(name, job.isDryRun())
                    : decompress(name, job.isDryRun());
            job.recordResult(result);
        } catch (Exception e) {
            log.warn("Archive job {}: {} failed: {}", job.getId(), name, e.toString());
            job.recordError(name, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String summary(ArchiveJob job) {
        String verb = job.getType() == ArchiveJob.Type.COMPRESS ? "compressed" : "decompressed";
        String prefix = job.isDryRun() ? "Dry run: would have " : "";
        return prefix + verb + " " + job.getSucceeded() + " file(s), skipped " + job.getSkipped()
                + ", failed " + job.getFailed() + "; " + humanBytes(job.getBytesBefore()) + " -> "
                + humanBytes(job.getBytesAfter());
    }

    /** Stored forms a job of this type works on, in order of preference when several exist. */
    private List<Format> candidateForms(ArchiveJob.Type type) {
        if (type == ArchiveJob.Type.DECOMPRESS) {
            return List.of(Format.RICE, Format.GZIP);
        }
        return format == ArchiveFormat.RICE ? List.of(Format.PLAIN, Format.GZIP) : List.of(Format.PLAIN);
    }

    private List<Path> selectCandidates(ArchiveJob.Type type, ArchiveSelection selection) throws IOException {
        List<String> extensions = selection.getExtensions() == null || selection.getExtensions().isEmpty()
                ? defaultExtensions
                : normalizeExtensions(selection.getExtensions());
        List<Format> forms = candidateForms(type);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime before = selection.getBefore();
        if (selection.getOlderThanDays() != null) {
            LocalDateTime cutoff = now.minusDays(selection.getOlderThanDays());
            before = before == null || cutoff.isBefore(before) ? cutoff : before;
        }
        String prefix = selection.getPrefix() == null ? null : selection.getPrefix().trim();

        Map<String, Path> matches = new LinkedHashMap<>();
        if (selection.getNames() != null && !selection.getNames().isEmpty()) {
            Set<String> logicalNames = new LinkedHashSet<>();
            for (String name : selection.getNames()) {
                if (name != null && !name.isBlank()) {
                    logicalNames.add(logicalName(name.trim()));
                }
            }
            for (String logicalName : logicalNames) {
                for (Format form : forms) {
                    Path path = imagePaths.resolve(logicalName + form.suffix());
                    if (Files.isRegularFile(path)) {
                        matches.put(logicalName, path);
                        break;
                    }
                }
            }
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagePaths.baseDir())) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    Format form = Format.ofFileName(name);
                    int rank = forms.indexOf(form);
                    if (rank < 0) {
                        continue;
                    }
                    String logicalName = form.logicalName(name);
                    if (!extensions.contains(extensionOf(logicalName))) {
                        continue;
                    }
                    if (prefix != null && !prefix.isEmpty() && !name.startsWith(prefix)) {
                        continue;
                    }
                    if (before != null || selection.getAfter() != null) {
                        LocalDateTime captured = capturedAt(path).orElse(null);
                        if (captured == null) {
                            continue;
                        }
                        if (before != null && !captured.isBefore(before)) {
                            continue;
                        }
                        if (selection.getAfter() != null && captured.isBefore(selection.getAfter())) {
                            continue;
                        }
                    }
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    if (!selection.isIncludeEmpty() && Files.size(path) == 0) {
                        continue;
                    }
                    Path existing = matches.get(logicalName);
                    if (existing == null || forms.indexOf(Format.ofFileName(existing.getFileName().toString())) > rank) {
                        matches.put(logicalName, path);
                    }
                }
            }
        }

        List<Path> result = new ArrayList<>(matches.values());
        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (selection.getLimit() != null && selection.getLimit() > 0 && result.size() > selection.getLimit()) {
            return new ArrayList<>(result.subList(0, selection.getLimit()));
        }
        return result;
    }

    // ------------------------------------------------------------------ stats

    public ArchiveStats stats(boolean refresh) throws IOException {
        ArchiveStats current = cachedStats;
        if (!refresh && current != null
                && Duration.between(current.generatedAt(), Instant.now()).compareTo(STATS_TTL) < 0) {
            return current;
        }
        synchronized (statsLock) {
            current = cachedStats;
            if (!refresh && current != null
                    && Duration.between(current.generatedAt(), Instant.now()).compareTo(STATS_TTL) < 0) {
                return current;
            }
            ArchiveStats fresh = scanStats();
            cachedStats = fresh;
            return fresh;
        }
    }

    public void invalidateStats() {
        cachedStats = null;
    }

    private ArchiveStats scanStats() throws IOException {
        long started = System.nanoTime();
        long totalFiles = 0, totalBytes = 0;
        long fitsPlainFiles = 0, fitsPlainBytes = 0, fitsGzFiles = 0, fitsGzBytes = 0, fitsFzFiles = 0, fitsFzBytes = 0;
        long jpgFiles = 0, jpgBytes = 0, otherFiles = 0, otherBytes = 0, emptyFiles = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(imagePaths.baseDir())) {
            for (Path path : stream) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue;
                }
                if (!attrs.isRegularFile()) {
                    continue;
                }
                String name = path.getFileName().toString();
                long size = attrs.size();
                totalFiles++;
                totalBytes += size;
                if (size == 0) {
                    emptyFiles++;
                }
                Format form = Format.ofFileName(name);
                String ext = extensionOf(form.logicalName(name));
                if (defaultExtensions.contains(ext)) {
                    switch (form) {
                        case GZIP -> {
                            fitsGzFiles++;
                            fitsGzBytes += size;
                        }
                        case RICE -> {
                            fitsFzFiles++;
                            fitsFzBytes += size;
                        }
                        case PLAIN -> {
                            fitsPlainFiles++;
                            fitsPlainBytes += size;
                        }
                    }
                } else if (JPG_EXTENSIONS.contains(ext)) {
                    jpgFiles++;
                    jpgBytes += size;
                } else {
                    otherFiles++;
                    otherBytes += size;
                }
            }
        }

        long diskTotal = 0, diskFree = 0;
        try {
            FileStore store = Files.getFileStore(imagePaths.baseDir());
            diskTotal = store.getTotalSpace();
            diskFree = store.getUsableSpace();
        } catch (IOException e) {
            log.warn("Cannot read file store for {}: {}", imagePaths.baseDir(), e.getMessage());
        }

        return new ArchiveStats(
                Instant.now(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                imagePaths.baseDir().toString(),
                totalFiles, totalBytes,
                fitsPlainFiles, fitsPlainBytes,
                fitsGzFiles, fitsGzBytes,
                fitsFzFiles, fitsFzBytes,
                jpgFiles, jpgBytes,
                otherFiles, otherBytes,
                emptyFiles,
                diskTotal, diskFree);
    }

    // ---------------------------------------------------------------- helpers

    /** The requested name without a .gz or .fz suffix. */
    public static String logicalName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("Image file name is empty");
        }
        String name = requestedName.trim();
        return Format.ofFileName(name).logicalName(name);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeExtensions(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String ext : raw) {
            if (ext == null) {
                continue;
            }
            String trimmed = ext.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            result.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
        }
        return Collections.unmodifiableList(result);
    }

    private boolean isTooRecent(Path path) throws IOException {
        if (minAgeMinutes == 0) {
            return false;
        }
        Instant modified = Files.getLastModifiedTime(path).toInstant();
        return modified.isAfter(Instant.now().minus(Duration.ofMinutes(minAgeMinutes)));
    }

    /** Capture time from the file name (preferred, mtimes were mass-touched once) or else the mtime. */
    private static Optional<LocalDateTime> capturedAt(Path path) {
        Matcher m = FILE_TIMESTAMP.matcher(path.getFileName().toString());
        if (m.find()) {
            try {
                return Optional.of(LocalDateTime.parse(m.group(1)));
            } catch (DateTimeParseException ignored) {
                // fall through to mtime
            }
        }
        try {
            return Optional.of(LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** GZIPOutputStream with a configurable deflate level. */
    private static final class LevelGzipOutputStream extends GZIPOutputStream {
        LevelGzipOutputStream(OutputStream out, int level) throws IOException {
            super(out, BUFFER);
            def.setLevel(level);
        }
    }
}
