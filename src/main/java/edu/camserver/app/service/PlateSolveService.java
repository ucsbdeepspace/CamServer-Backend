package edu.camserver.app.service;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.platesolve.PlateSolveCrop;
import edu.camserver.app.model.platesolve.PlateSolveProgress;
import edu.camserver.app.model.platesolve.PlateSolveResult;
import edu.camserver.app.model.platesolve.PlateSolveSolution;
import edu.camserver.app.model.platesolve.PlateSolveStar;
import edu.camserver.app.model.platesolve.PlateSolveStarIdentifier;
import edu.camserver.app.model.platesolve.PlateSolveStarLink;
import edu.camserver.app.model.platesolve.PlateSolveStatus;
import edu.camserver.app.model.archive.StoredImage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlateSolveService {
    private static final Pattern FIELD_CENTER_PATTERN = Pattern.compile(
            "Field center:.*?\\(([0-9.+\\-Ee]+),\\s*([0-9.+\\-Ee]+)\\).*?deg"
    );
    private static final Pattern FIELD_SIZE_PATTERN = Pattern.compile(
            "Field size:\\s*([0-9.+\\-Ee]+)\\s*x\\s*([0-9.+\\-Ee]+)\\s*degrees"
    );
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(HIP|HD|HR|SAO|TYC)\\s*([A-Za-z0-9+._\\-]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GAIA_IDENTIFIER_PATTERN = Pattern.compile(
            "\\bGAIA(?:\\s+DR\\d+)?\\s*([0-9]+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final int FITS_BLOCK_SIZE = 2880;
    private static final List<String> FITS_EXTENSIONS = List.of(".fits", ".fit", ".fts");
    private static final List<String> RASTER_EXTENSIONS = List.of(".jpg", ".jpeg", ".png");
    private static final URI DEFAULT_ONLINE_CATALOG_URL = URI.create("https://simbad.cds.unistra.fr/simbad/sim-tap/sync");
    private static final int QHY5III678_EFFECTIVE_WIDTH_PX = 3856;
    private static final int QHY5III678_EFFECTIVE_HEIGHT_PX = 2180;
    private static final double QHY5III678_PIXEL_SIZE_UM = 2.0;
    private static final double[] FISHEYE_RADIAL_POWERS = {
            0.64, 0.68, 0.72, 0.76, 0.80, 0.84, 0.88, 0.92, 0.96, 1.00, 1.06, 1.12, 1.18, 1.25, 1.32, 1.40
    };
    private static final double[] DEFAULT_RADIAL_POWERS = {0.92, 1.00, 1.08};
    private static final int UNDISTORTED_SOLVE_SIZE_PX = 1600;
    private static final double UNDISTORTED_SOLVE_FIELD_WIDTH_DEG = 100.0;
    private static final double UNDISTORTED_SOLVE_SEARCH_RADIUS_DEG = 70.0;
    private static final int UNDISTORTED_SOLVE_TWEAK_ORDER = 2;
    private static final int UNDISTORTED_XYLIST_MAX_STARS = 240;
    private static final double UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG = 32.0;
    private static final double UNDISTORTED_XYLIST_MARGIN_PX = 48.0;
    private static final double UNDISTORTED_XYLIST_MIN_DISTANCE_PX = 12.0;
    private static final int UNDISTORTED_XYLIST_GRID = 16;
    private static final int UNDISTORTED_XYLIST_MAX_PER_CELL = 2;
    private static final int MIN_UNDISTORTED_WCS_CATALOG_MATCHES = 4;
    private static final int GUIDED_WCS_MAX_CATALOG_MARKERS = 120;
    private static final double GUIDED_WCS_CATALOG_MARKER_MAG_LIMIT = 6.2;
    private static final double GUIDED_WCS_CATALOG_SNAP_FRACTION = 0.09;
    private static final double GUIDED_WCS_CATALOG_SNAP_MIN_PX = 45.0;
    private static final double GUIDED_WCS_CATALOG_SNAP_MAX_PX = 120.0;
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    private final ImageService imageService;
    private final ImagePaths imagePaths;
    private final ImageArchiveService archiveService;
    private final PlateSolveMaskService maskService;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Map<Long, PlateSolveResult> resultCache = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<PlateSolveResult>> runningJobs = new ConcurrentHashMap<>();
    private final Map<Long, PlateSolveProgress> progressCache = new ConcurrentHashMap<>();
    private final Map<String, CameraCalibration> calibrationCache = new ConcurrentHashMap<>();
    private final Map<String, AllSkyProjection> allSkyProjectionCache = new ConcurrentHashMap<>();
    private final Path workDir;
    private final Path indexDir;
    private final boolean enabled;
    private final String solverCommand;
    private final String resolvedSolverCommand;
    private final String fitWcsCommand;
    private final String resolvedFitWcsCommand;
    private final String fitsImcopyCommand;
    private final String resolvedFitsImcopyCommand;
    private final int timeoutSeconds;
    private final int maxStars;
    private final int maxStarArea;
    private final int maxStarDiameter;
    private final int maxStarBackground;
    private final int cropThreshold;
    private final int fitsCropThreshold;
    private final int starMinContrast;
    private final int fitsStarMinContrast;
    private final double starContrastPercentile;
    private final double fitsStarContrastPercentile;
    private final double scaleLowDeg;
    private final double scaleHighDeg;
    private final int downsample;
    private final double siteLatitudeDeg;
    private final double siteLongitudeDeg;
    private final double searchRadiusDeg;
    private final boolean calibrationCacheEnabled;
    private final Duration calibrationCacheMaxAge;
    private final String catalogPath;
    private final double catalogMatchRadiusDeg;
    private final double allSkyCatalogMatchRadiusDeg;
    private final boolean onlineCatalogEnabled;
    private final URI onlineCatalogUrl;
    private final Path onlineCatalogCacheFile;
    private final Duration onlineCatalogCacheTtl;
    private final double onlineCatalogMagnitudeLimit;
    private final int onlineCatalogMaxRows;
    private final Duration onlineCatalogTimeout;
    private volatile List<CatalogStar> catalogStars;
    private volatile List<CatalogStar> onlineCatalogStars;

    public PlateSolveService(
            ImageService imageService,
            ImagePaths imagePaths,
            ImageArchiveService archiveService,
            PlateSolveMaskService maskService,
            @Value("${app.plate-solve.enabled:true}") boolean enabled,
            @Value("${app.plate-solve.solver-command:solve-field}") String solverCommand,
            @Value("${app.plate-solve.fit-wcs-command:fit-wcs}") String fitWcsCommand,
            @Value("${app.plate-solve.fits-imcopy-command:imcopy}") String fitsImcopyCommand,
            @Value("${app.plate-solve.index-dir:}") String configuredIndexDir,
            @Value("${app.plate-solve.work-dir:}") String configuredWorkDir,
            @Value("${app.plate-solve.timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.plate-solve.max-stars:800}") int maxStars,
            @Value("${app.plate-solve.max-star-area:80}") int maxStarArea,
            @Value("${app.plate-solve.max-star-diameter:18}") int maxStarDiameter,
            @Value("${app.plate-solve.max-star-background:95}") int maxStarBackground,
            @Value("${app.plate-solve.crop-threshold:12}") int cropThreshold,
            @Value("${app.plate-solve.fits-crop-threshold:32}") int fitsCropThreshold,
            @Value("${app.plate-solve.star-min-contrast:18}") int starMinContrast,
            @Value("${app.plate-solve.fits-star-min-contrast:42}") int fitsStarMinContrast,
            @Value("${app.plate-solve.star-contrast-percentile:0.9985}") double starContrastPercentile,
            @Value("${app.plate-solve.fits-star-contrast-percentile:0.9994}") double fitsStarContrastPercentile,
            @Value("${app.plate-solve.scale-low-deg:150}") double scaleLowDeg,
            @Value("${app.plate-solve.scale-high-deg:220}") double scaleHighDeg,
            @Value("${app.plate-solve.downsample:2}") int downsample,
            @Value("${app.plate-solve.site-latitude-deg:34.41403}") double siteLatitudeDeg,
            @Value("${app.plate-solve.site-longitude-deg:-119.84300}") double siteLongitudeDeg,
            @Value("${app.plate-solve.search-radius-deg:110}") double searchRadiusDeg,
            @Value("${app.plate-solve.calibration-cache-enabled:true}") boolean calibrationCacheEnabled,
            @Value("${app.plate-solve.calibration-cache-max-age-minutes:10}") long calibrationCacheMaxAgeMinutes,
            @Value("${app.plate-solve.catalog-path:}") String catalogPath,
            @Value("${app.plate-solve.catalog-match-radius-deg:0.05}") double catalogMatchRadiusDeg,
            @Value("${app.plate-solve.all-sky-catalog-match-radius-deg:0.05}") double allSkyCatalogMatchRadiusDeg,
            @Value("${app.plate-solve.online-catalog.enabled:true}") boolean onlineCatalogEnabled,
            @Value("${app.plate-solve.online-catalog.url:https://simbad.cds.unistra.fr/simbad/sim-tap/sync}") String onlineCatalogUrl,
            @Value("${app.plate-solve.online-catalog.cache-file:}") String onlineCatalogCacheFile,
            @Value("${app.plate-solve.online-catalog.cache-ttl-hours:720}") long onlineCatalogCacheTtlHours,
            @Value("${app.plate-solve.online-catalog.magnitude-limit:8.0}") double onlineCatalogMagnitudeLimit,
            @Value("${app.plate-solve.online-catalog.max-rows:30000}") int onlineCatalogMaxRows,
            @Value("${app.plate-solve.online-catalog.timeout-ms:10000}") long onlineCatalogTimeoutMs,
            @Value("${app.plate-solve.worker-threads:2}") int workerThreads) {
        this.imageService = imageService;
        this.imagePaths = imagePaths;
        this.archiveService = archiveService;
        this.maskService = maskService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, onlineCatalogTimeoutMs)))
                .build();
        this.enabled = enabled;
        this.solverCommand = solverCommand == null || solverCommand.isBlank() ? "solve-field" : solverCommand;
        this.resolvedSolverCommand = resolveSolverCommand(this.solverCommand);
        this.fitWcsCommand = fitWcsCommand == null || fitWcsCommand.isBlank() ? "fit-wcs" : fitWcsCommand;
        this.resolvedFitWcsCommand = resolveSolverCommand(this.fitWcsCommand);
        this.fitsImcopyCommand = fitsImcopyCommand == null || fitsImcopyCommand.isBlank() ? "imcopy" : fitsImcopyCommand;
        this.resolvedFitsImcopyCommand = resolveSolverCommand(this.fitsImcopyCommand);
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.maxStars = Math.max(50, maxStars);
        this.maxStarArea = Math.max(4, maxStarArea);
        this.maxStarDiameter = Math.max(4, maxStarDiameter);
        this.maxStarBackground = Math.max(0, Math.min(255, maxStarBackground));
        this.cropThreshold = Math.max(0, Math.min(255, cropThreshold));
        this.fitsCropThreshold = Math.max(0, Math.min(255, fitsCropThreshold));
        this.starMinContrast = Math.max(1, Math.min(255, starMinContrast));
        this.fitsStarMinContrast = Math.max(this.starMinContrast, Math.min(255, fitsStarMinContrast));
        this.starContrastPercentile = clampPercentile(starContrastPercentile, 0.9985);
        this.fitsStarContrastPercentile = clampPercentile(fitsStarContrastPercentile, 0.9994);
        this.scaleLowDeg = scaleLowDeg;
        this.scaleHighDeg = scaleHighDeg;
        this.downsample = Math.max(1, downsample);
        this.siteLatitudeDeg = siteLatitudeDeg;
        this.siteLongitudeDeg = siteLongitudeDeg;
        this.searchRadiusDeg = Math.max(0, Math.min(180, searchRadiusDeg));
        this.calibrationCacheEnabled = calibrationCacheEnabled;
        this.calibrationCacheMaxAge = Duration.ofMinutes(Math.max(0, calibrationCacheMaxAgeMinutes));
        this.catalogPath = catalogPath == null ? "" : catalogPath;
        this.catalogMatchRadiusDeg = Math.max(0.001, catalogMatchRadiusDeg);
        this.allSkyCatalogMatchRadiusDeg = Math.max(0.001, allSkyCatalogMatchRadiusDeg);
        this.onlineCatalogEnabled = onlineCatalogEnabled;
        this.onlineCatalogUrl = parseUri(onlineCatalogUrl).orElse(DEFAULT_ONLINE_CATALOG_URL);
        this.onlineCatalogCacheTtl = Duration.ofHours(Math.max(1, onlineCatalogCacheTtlHours));
        this.onlineCatalogMagnitudeLimit = Math.max(-2.0, Math.min(20.0, onlineCatalogMagnitudeLimit));
        this.onlineCatalogMaxRows = Math.max(100, onlineCatalogMaxRows);
        this.onlineCatalogTimeout = Duration.ofMillis(Math.max(500, onlineCatalogTimeoutMs));
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerThreads));
        this.workDir = configuredWorkDir == null || configuredWorkDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "camserver-plate-solve")
                : Path.of(configuredWorkDir);
        this.indexDir = configuredIndexDir == null || configuredIndexDir.isBlank()
                ? null
                : Path.of(configuredIndexDir);
        this.onlineCatalogCacheFile = onlineCatalogCacheFile == null || onlineCatalogCacheFile.isBlank()
                ? this.workDir.resolve("catalog-cache").resolve("simbad-bright-stars.csv")
                : Path.of(onlineCatalogCacheFile);
    }

    public PlateSolveResult getStatus(long imgId) {
        PlateSolveResult cached = resultCache.get(imgId);
        if (cached != null) {
            return cached.withCached(true);
        }

        CompletableFuture<PlateSolveResult> job = runningJobs.get(imgId);
        if (job == null) {
            return statusOnly(imgId, PlateSolveStatus.NOT_STARTED, "Plate solve has not been started.");
        }

        if (!job.isDone()) {
            PlateSolveProgress progress = progressCache.get(imgId);
            return statusOnly(
                    imgId,
                    PlateSolveStatus.RUNNING,
                    progress == null ? "Plate solve is running." : progress.detail()
            );
        }

        return job.getNow(statusOnly(imgId, PlateSolveStatus.FAILED, "Plate solve did not produce a result."));
    }

    public PlateSolveResult start(long imgId, boolean force, boolean wait) {
        if (!force) {
            PlateSolveResult cached = resultCache.get(imgId);
            if (cached != null) {
                return cached.withCached(true);
            }
        } else {
            resultCache.remove(imgId);
            progressCache.remove(imgId);
        }

        updateProgress(imgId, "Queued", 2, "Plate solve has been queued.", List.of());

        CompletableFuture<PlateSolveResult> job = runningJobs.computeIfAbsent(imgId, id ->
                CompletableFuture.supplyAsync(() -> solve(id, force), executor)
                        .whenComplete((result, error) -> {
                            runningJobs.remove(id);
                            if (result != null) {
                                resultCache.put(id, result);
                            }
                        })
        );

        if (wait) {
            return job.join();
        }

        return statusOnly(imgId, PlateSolveStatus.QUEUED, "Plate solve has been queued.");
    }

    private PlateSolveResult solve(long imgId, boolean force) {
        try {
            updateProgress(imgId, "Preparing", 5, "Creating solver workspace.", List.of());
            Files.createDirectories(workDir);
            Image image = imageService.findById(imgId);
            Path sourcePath = resolveImagePath(image);
            updateProgress(imgId, "Loading image", 8, "Reading source image: " + sourcePath.getFileName(), List.of());
            SourceFrame sourceFrame = loadSourceFrame(imgId, sourcePath);
            BufferedImage source = sourceFrame.image();
            if (source == null) {
                return statusOnly(imgId, PlateSolveStatus.FAILED, "Image file could not be decoded.");
            }

            updateProgress(imgId, "Preprocessing", 14, "Cropping all-sky image and building ignore masks.", List.of());
            CropImage cropImage = cropUsefulArea(source, sourceFrame.cropThreshold());
            boolean[] ignoreMask = maskService.buildIgnoreMask(source, image, cropImage.crop(), sourcePath);
            Path croppedPath = writeCrop(imgId, applyIgnoreMask(cropImage.image(), ignoreMask));
            updateProgress(
                    imgId,
                    "Detecting stars",
                    24,
                    "Detecting local point sources from " + sourceFrame.sourceKind() + " pixels.",
                    List.of()
            );
            List<PlateSolveStar> stars = detectStars(
                    cropImage.image(),
                    cropImage.crop(),
                    ignoreMask,
                    sourceFrame.starMinContrast(),
                    sourceFrame.starContrastPercentile()
            );
            Optional<PlateSolveResult> cachedCalibrationResult = force
                    ? Optional.empty()
                    : applyCachedCalibration(imgId, image, cropImage.crop(), stars);

            if (cachedCalibrationResult.isPresent()) {
                updateProgress(imgId, "Cached calibration", 100, "Used cached WCS calibration.", List.of());
                return cachedCalibrationResult.get();
            }

            if (!enabled) {
                Optional<PlateSolveResult> allSkyResult = completeWithAllSkyFallback(
                        imgId,
                        image,
                        source,
                        sourceFrame.cropThreshold(),
                        cropImage.crop(),
                        stars,
                        "Plate solving is disabled; using fixed-camera all-sky calibration.",
                        null
                );
                if (allSkyResult.isPresent()) {
                    return allSkyResult.get();
                }

                updateProgress(imgId, "Solver disabled", 100, "Plate solving is disabled.", List.of());
                return complete(
                        imgId,
                        PlateSolveStatus.SOLVER_UNAVAILABLE,
                        "Plate solving is disabled; returning locally detected stars only.",
                        cropImage.crop(),
                        unavailableSolution(null),
                        stars
                );
            }

            if (!isCommandAvailable(resolvedSolverCommand)) {
                Optional<PlateSolveResult> allSkyResult = completeWithAllSkyFallback(
                        imgId,
                        image,
                        source,
                        sourceFrame.cropThreshold(),
                        cropImage.crop(),
                        stars,
                        "Local solve-field command was not found; using fixed-camera all-sky calibration.",
                        null
                );
                if (allSkyResult.isPresent()) {
                    return allSkyResult.get();
                }

                updateProgress(imgId, "Solver unavailable", 100, "Local solve-field command was not found.", List.of());
                return complete(
                        imgId,
                        PlateSolveStatus.SOLVER_UNAVAILABLE,
                        "Local solve-field command was not found; install Astrometry.net locally to solve sky coordinates.",
                        cropImage.crop(),
                        unavailableSolution(null),
                        stars
                );
            }

            boolean triedUndistortedAstrometry = false;
            if (isQhy5iii678Frame(cropImage.crop())) {
                triedUndistortedAstrometry = true;
                Optional<PlateSolveResult> undistortedResult = completeWithUndistortedAstrometry(
                        imgId,
                        image,
                        source,
                        sourceFrame.cropThreshold(),
                        cropImage.crop(),
                        stars,
                        "Calibrating fisheye projection before Astrometry.net solve.",
                        null
                );
                if (undistortedResult.isPresent()) {
                    return undistortedResult.get();
                }
            }

            updateProgress(imgId, "Solving WCS", 35, "Starting local Astrometry.net solve-field.", List.of());
            SolverRun solverRun = runSolveField(imgId, image, croppedPath);
            updateProgress(imgId, "Reading WCS", solverRun.solved() ? 82 : 100, "Reading solver output.", tailLog(solverRun.log(), 12));
            WcsHeader wcsHeader = solverRun.solved()
                    ? parseWcsHeader(solverRun.wcsPath()).orElse(null)
                    : null;
            PlateSolveSolution solution = parseSolution(solverRun, wcsHeader);
            updateProgress(imgId, "Matching catalog", solution.solved() ? 90 : 100, "Matching solved coordinates to local catalog.", tailLog(solverRun.log(), 12));
            List<PlateSolveStar> identifiedStars = wcsHeader == null
                    ? stars
                    : identifyStarsWithWcs(stars, wcsHeader, cropImage.crop());
            boolean solvedWithWcs = solution.solved() && wcsHeader != null;
            if (!solvedWithWcs) {
                if (!triedUndistortedAstrometry) {
                    Optional<PlateSolveResult> undistortedResult = completeWithUndistortedAstrometry(
                            imgId,
                            image,
                            source,
                            sourceFrame.cropThreshold(),
                            cropImage.crop(),
                            stars,
                            "Astrometry.net did not solve the raw fisheye frame; solving an undistorted zenith cutout.",
                            solverRun.log()
                    );
                    if (undistortedResult.isPresent()) {
                        return undistortedResult.get();
                    }
                }

                Optional<PlateSolveResult> allSkyResult = completeWithAllSkyFallback(
                        imgId,
                        image,
                        source,
                        sourceFrame.cropThreshold(),
                        cropImage.crop(),
                        stars,
                        "Astrometry.net did not produce a usable WCS; using fixed-camera all-sky calibration.",
                        solverRun.log()
                );
                if (allSkyResult.isPresent()) {
                    return allSkyResult.get();
                }
            }

            PlateSolveStatus status = solvedWithWcs ? PlateSolveStatus.SOLVED : PlateSolveStatus.FAILED;
            String message = solvedWithWcs
                    ? "Plate solve completed locally."
                    : "Local solver ran but did not produce a WCS solution.";

            if (solution.solved() && wcsHeader != null) {
                cacheCalibration(image, cropImage.crop(), wcsHeader, solution);
            }

            updateProgress(imgId, status == PlateSolveStatus.SOLVED ? "Solved" : "Failed", 100, message, tailLog(solverRun.log(), 12));
            return complete(imgId, status, message, cropImage.crop(), solution, identifiedStars);
        } catch (Exception e) {
            updateProgress(imgId, "Failed", 100, e.getMessage(), List.of());
            return statusOnly(imgId, PlateSolveStatus.FAILED, e.getMessage());
        }
    }

    private Path resolveImagePath(Image image) {
        String imgPath = image.getImgPath();
        Path fileName = Path.of(imgPath).getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Image path is empty.");
        }

        String name = fileName.toString();
        List<String> candidates = new ArrayList<>();
        Optional<String> extension = fileExtension(name)
                .filter(this::isKnownImageExtension);
        String baseName = extension
                .map(value -> name.substring(0, name.length() - value.length()))
                .orElse(name);

        for (String fitsExtension : FITS_EXTENSIONS) {
            candidates.add(baseName + fitsExtension);
        }

        if (extension.isPresent()) {
            candidates.add(name);
        } else {
            candidates.add(baseName + ".jpg");
            candidates.add(baseName + ".jpeg");
            candidates.add(baseName + ".png");
            candidates.add(baseName);
        }

        List<String> distinctCandidates = candidates.stream().distinct().toList();
        for (String candidate : distinctCandidates) {
            Path resolved = imagePaths.resolve(candidate).normalize();
            if (Files.exists(resolved)) {
                return resolved;
            }
        }

        // The frame may be archived (.gz or Rice .fz); expand it to a temp file the solver tools can read.
        for (String candidate : distinctCandidates) {
            Optional<StoredImage> stored = archiveService.locate(candidate);
            if (stored.isPresent() && stored.get().compressed()) {
                try {
                    return archiveService.materialize(stored.get());
                } catch (IOException e) {
                    throw new IllegalArgumentException("Archived image could not be expanded: " + candidate, e);
                }
            }
        }

        throw new IllegalArgumentException("Image file was not found: " + fileName);
    }

    private SourceFrame loadSourceFrame(long imgId, Path sourcePath) throws IOException, InterruptedException {
        if (isFitsPath(sourcePath)) {
            updateProgress(imgId, "Loading FITS", 10, "Reading FITS source: " + sourcePath.getFileName(), List.of());
            return new SourceFrame(
                    readFitsAsImage(imgId, sourcePath),
                    sourcePath,
                    "FITS",
                    fitsCropThreshold,
                    fitsStarMinContrast,
                    fitsStarContrastPercentile
            );
        }

        BufferedImage source = ImageIO.read(sourcePath.toFile());
        return new SourceFrame(
                source,
                sourcePath,
                "JPG",
                cropThreshold,
                starMinContrast,
                starContrastPercentile
        );
    }

    private BufferedImage readFitsAsImage(long imgId, Path sourcePath) throws IOException, InterruptedException {
        Optional<FitsImage> directImage = readFirstPlainFitsImage(sourcePath);
        if (directImage.isPresent()) {
            return fitsImageToBufferedImage(directImage.get());
        }

        Optional<Integer> compressedHduIndex = firstCompressedFitsImageHdu(sourcePath);
        if (compressedHduIndex.isEmpty()) {
            throw new IOException("FITS file does not contain a readable image HDU.");
        }

        if (!isCommandAvailable(resolvedFitsImcopyCommand)) {
            throw new IOException("Compressed FITS image requires local imcopy, but it was not found: " + fitsImcopyCommand);
        }

        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path extractedPath = imageWorkDir.resolve("source-from-fits.fits");
        Path logPath = imageWorkDir.resolve("imcopy.log");
        Files.deleteIfExists(extractedPath);
        Files.deleteIfExists(logPath);

        String hduSpecifier = sourcePath + "[" + compressedHduIndex.get() + "]";
        List<String> command = List.of(resolvedFitsImcopyCommand, hduSpecifier, extractedPath.toString());
        updateProgress(imgId, "Loading FITS", 11, "Extracting compressed FITS image: " + String.join(" ", command), List.of());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();
        boolean finished = process.waitFor(Math.min(timeoutSeconds, 30), TimeUnit.SECONDS);
        String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out while extracting compressed FITS image with imcopy.");
        }

        if (process.exitValue() != 0 || !Files.exists(extractedPath)) {
            throw new IOException("imcopy failed while extracting compressed FITS image: " + log);
        }

        return fitsImageToBufferedImage(readFirstPlainFitsImage(extractedPath)
                .orElseThrow(() -> new IOException("Extracted FITS image could not be decoded.")));
    }

    private Optional<FitsImage> readFirstPlainFitsImage(Path fitsPath) throws IOException {
        byte[] bytes = Files.readAllBytes(fitsPath);
        int offset = 0;
        int hduIndex = 0;

        while (offset >= 0 && offset < bytes.length) {
            Optional<FitsHdu> parsed = parseFitsHdu(bytes, offset, hduIndex);
            if (parsed.isEmpty()) {
                break;
            }

            FitsHdu hdu = parsed.get();
            if (hdu.plainImage()) {
                return Optional.of(readFitsImage(bytes, hdu));
            }

            if (hdu.nextOffset() <= offset) {
                break;
            }
            offset = hdu.nextOffset();
            hduIndex++;
        }

        return Optional.empty();
    }

    private Optional<Integer> firstCompressedFitsImageHdu(Path fitsPath) throws IOException {
        byte[] bytes = Files.readAllBytes(fitsPath);
        int offset = 0;
        int hduIndex = 0;

        while (offset >= 0 && offset < bytes.length) {
            Optional<FitsHdu> parsed = parseFitsHdu(bytes, offset, hduIndex);
            if (parsed.isEmpty()) {
                break;
            }

            FitsHdu hdu = parsed.get();
            if (hdu.compressedImage()) {
                return Optional.of(hdu.index());
            }

            if (hdu.nextOffset() <= offset) {
                break;
            }
            offset = hdu.nextOffset();
            hduIndex++;
        }

        return Optional.empty();
    }

    private Optional<FitsHdu> parseFitsHdu(byte[] bytes, int offset, int hduIndex) {
        if (offset < 0 || offset + 80 > bytes.length) {
            return Optional.empty();
        }

        Map<String, String> header = new HashMap<>();
        int cursor = offset;
        boolean foundEnd = false;
        while (cursor + 80 <= bytes.length) {
            String card = new String(bytes, cursor, 80, StandardCharsets.US_ASCII);
            String key = card.substring(0, 8).trim();
            cursor += 80;

            if ("END".equals(key)) {
                foundEnd = true;
                break;
            }

            if (!key.isBlank() && card.length() > 10 && card.charAt(8) == '=') {
                header.put(key, cleanFitsHeaderValue(card.substring(10)));
            }
        }

        if (!foundEnd) {
            return Optional.empty();
        }

        int headerBytes = cursor - offset;
        int headerEnd = offset + paddedFitsSize(headerBytes);
        int bitpix = headerInt(header, "BITPIX", 8);
        int naxis = Math.max(0, headerInt(header, "NAXIS", 0));
        int[] axes = new int[naxis];
        long elementCount = 1;
        for (int axis = 0; axis < naxis; axis++) {
            axes[axis] = Math.max(0, headerInt(header, "NAXIS" + (axis + 1), 0));
            elementCount *= axes[axis];
        }
        if (naxis == 0) {
            elementCount = 0;
        }

        int bytesPerPixel = Math.max(0, Math.abs(bitpix) / 8);
        long dataBytes = elementCount * bytesPerPixel;
        long pcount = Math.max(0, headerLong(header, "PCOUNT", 0));
        long gcount = Math.max(1, headerLong(header, "GCOUNT", 1));
        dataBytes = dataBytes * gcount + pcount;
        int nextOffset = headerEnd + paddedFitsSize(dataBytes);
        String extension = header.getOrDefault("XTENSION", "");
        boolean table = extension.equalsIgnoreCase("BINTABLE") || extension.equalsIgnoreCase("TABLE");
        boolean compressedImage = headerBoolean(header, "ZIMAGE", false);
        boolean plainImage = !compressedImage && !table && naxis >= 2 && elementCount > 0 && bytesPerPixel > 0;

        return Optional.of(new FitsHdu(
                hduIndex,
                header,
                headerEnd,
                Math.max(0, nextOffset),
                bitpix,
                axes,
                headerDoubleValue(header, "BSCALE", 1.0),
                headerDoubleValue(header, "BZERO", 0.0),
                plainImage,
                compressedImage
        ));
    }

    private FitsImage readFitsImage(byte[] bytes, FitsHdu hdu) throws IOException {
        int width = hdu.axes()[0];
        int height = hdu.axes()[1];
        int channels = hdu.axes().length >= 3 ? Math.max(1, Math.min(3, hdu.axes()[2])) : 1;
        int bytesPerPixel = Math.abs(hdu.bitpix()) / 8;
        int planeSize = Math.multiplyExact(width, height);
        int pixelCount = Math.multiplyExact(planeSize, channels);
        long requiredBytes = (long) pixelCount * bytesPerPixel;
        if (hdu.dataOffset() + requiredBytes > bytes.length) {
            throw new IOException("FITS image data is shorter than its header declares.");
        }

        float[] pixels = new float[pixelCount];
        for (int index = 0; index < pixelCount; index++) {
            int byteOffset = hdu.dataOffset() + index * bytesPerPixel;
            pixels[index] = (float) (readFitsPixel(bytes, byteOffset, hdu.bitpix()) * hdu.bscale() + hdu.bzero());
        }

        return new FitsImage(width, height, channels, pixels);
    }

    private BufferedImage fitsImageToBufferedImage(FitsImage fitsImage) {
        int width = fitsImage.width();
        int height = fitsImage.height();
        int channels = fitsImage.channels();
        int planeSize = width * height;
        double[] lows = new double[channels];
        double[] highs = new double[channels];

        for (int channel = 0; channel < channels; channel++) {
            int offset = channel * planeSize;
            lows[channel] = sampledFitsPercentile(fitsImage.pixels(), offset, planeSize, 0.002);
            highs[channel] = sampledFitsPercentile(fitsImage.pixels(), offset, planeSize, 0.999);
            if (highs[channel] <= lows[channel]) {
                highs[channel] = lows[channel] + 1.0;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = row + x;
                int red = scaleFitsPixel(fitsImage.pixels()[pixel], lows[0], highs[0]);
                int green = channels > 1
                        ? scaleFitsPixel(fitsImage.pixels()[planeSize + pixel], lows[1], highs[1])
                        : red;
                int blue = channels > 2
                        ? scaleFitsPixel(fitsImage.pixels()[planeSize * 2 + pixel], lows[2], highs[2])
                        : green;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }

        return image;
    }

    private double sampledFitsPercentile(float[] pixels, int offset, int length, double percentile) {
        int stride = Math.max(1, length / 200_000);
        double[] sample = new double[(length + stride - 1) / stride];
        int count = 0;

        for (int index = 0; index < length; index += stride) {
            float value = pixels[offset + index];
            if (Float.isFinite(value)) {
                sample[count++] = value;
            }
        }

        if (count == 0) {
            return 0;
        }

        Arrays.sort(sample, 0, count);
        int percentileIndex = (int) Math.floor((count - 1) * Math.max(0, Math.min(1, percentile)));
        return sample[percentileIndex];
    }

    private int scaleFitsPixel(float value, double low, double high) {
        if (!Float.isFinite(value)) {
            return 0;
        }

        double scaled = (value - low) / Math.max(1.0, high - low);
        scaled = Math.max(0.0, Math.min(1.0, scaled));
        return (int) Math.round(scaled * 255.0);
    }

    private double readFitsPixel(byte[] bytes, int offset, int bitpix) throws IOException {
        return switch (bitpix) {
            case 8 -> bytes[offset] & 0xff;
            case 16 -> (short) (((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff));
            case 32 -> ((bytes[offset] & 0xff) << 24)
                    | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8)
                    | (bytes[offset + 3] & 0xff);
            case -32 -> Float.intBitsToFloat(
                    ((bytes[offset] & 0xff) << 24)
                            | ((bytes[offset + 1] & 0xff) << 16)
                            | ((bytes[offset + 2] & 0xff) << 8)
                            | (bytes[offset + 3] & 0xff)
            );
            case -64 -> Double.longBitsToDouble(
                    ((long) (bytes[offset] & 0xff) << 56)
                            | ((long) (bytes[offset + 1] & 0xff) << 48)
                            | ((long) (bytes[offset + 2] & 0xff) << 40)
                            | ((long) (bytes[offset + 3] & 0xff) << 32)
                            | ((long) (bytes[offset + 4] & 0xff) << 24)
                            | ((long) (bytes[offset + 5] & 0xff) << 16)
                            | ((long) (bytes[offset + 6] & 0xff) << 8)
                            | (bytes[offset + 7] & 0xff)
            );
            default -> throw new IOException("Unsupported FITS BITPIX value: " + bitpix);
        };
    }

    private String cleanFitsHeaderValue(String rawValue) {
        String value = rawValue.split("/", 2)[0].trim();
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private int headerInt(Map<String, String> header, String key, int fallback) {
        try {
            return Integer.parseInt(header.getOrDefault(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long headerLong(Map<String, String> header, String key, long fallback) {
        try {
            return Long.parseLong(header.getOrDefault(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double headerDoubleValue(Map<String, String> header, String key, double fallback) {
        try {
            return Double.parseDouble(header.getOrDefault(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean headerBoolean(Map<String, String> header, String key, boolean fallback) {
        String value = header.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("T") || value.equalsIgnoreCase("TRUE");
    }

    private int paddedFitsSize(long size) {
        if (size <= 0) {
            return 0;
        }
        return (int) (((size + FITS_BLOCK_SIZE - 1) / FITS_BLOCK_SIZE) * FITS_BLOCK_SIZE);
    }

    private boolean isFitsPath(Path path) {
        return fileExtension(path.getFileName().toString())
                .map(FITS_EXTENSIONS::contains)
                .orElse(false);
    }

    private boolean isKnownImageExtension(String extension) {
        return FITS_EXTENSIONS.contains(extension) || RASTER_EXTENSIONS.contains(extension);
    }

    private Optional<String> fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    private String fileStem(Path path) {
        String fileName = path.getFileName().toString();
        return fileExtension(fileName)
                .map(extension -> fileName.substring(0, fileName.length() - extension.length()))
                .orElse(fileName);
    }

    private CropImage cropUsefulArea(BufferedImage source, int threshold) {
        int width = source.getWidth();
        int height = source.getHeight();
        int step = Math.max(1, Math.min(width, height) / 1200);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int luminance = luminance(source.getRGB(x, y));
                if (luminance > threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            PlateSolveCrop crop = new PlateSolveCrop(0, 0, width, height, width, height);
            return new CropImage(toRgb(source), crop);
        }

        int margin = Math.max(8, Math.min(width, height) / 100);
        minX = Math.max(0, minX - margin);
        minY = Math.max(0, minY - margin);
        maxX = Math.min(width - 1, maxX + margin);
        maxY = Math.min(height - 1, maxY + margin);

        int cropWidth = maxX - minX + 1;
        int cropHeight = maxY - minY + 1;
        PlateSolveCrop crop = new PlateSolveCrop(minX, minY, cropWidth, cropHeight, width, height);
        BufferedImage cropped = toRgb(source.getSubimage(minX, minY, cropWidth, cropHeight));
        return new CropImage(cropped, crop);
    }

    private Path writeCrop(long imgId, BufferedImage cropped) throws IOException {
        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path croppedPath = imageWorkDir.resolve("crop.jpg");
        ImageIO.write(cropped, "jpg", croppedPath.toFile());
        return croppedPath;
    }

    private BufferedImage applyIgnoreMask(BufferedImage image, boolean[] ignoreMask) {
        BufferedImage masked = toRgb(image);
        int width = masked.getWidth();
        int height = masked.getHeight();

        if (ignoreMask.length != width * height) {
            return masked;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ignoreMask[y * width + x]) {
                    masked.setRGB(x, y, 0);
                }
            }
        }

        return masked;
    }

    private List<PlateSolveStar> detectStars(
            BufferedImage image,
            PlateSolveCrop crop,
            boolean[] ignoreMask,
            int minContrast,
            double contrastPercentile) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] gray = new int[width * height];
        long[] integral = new long[(width + 1) * (height + 1)];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            long running = 0;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                int value = ignoreMask.length == width * height && ignoreMask[index] ? 0 : luminance(image.getRGB(x, y));
                gray[index] = value;
                running += value;
                integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + running;
            }
        }

        int backgroundRadius = Math.max(6, Math.min(width, height) / 180);
        int[] background = new int[width * height];
        int[] contrast = new int[width * height];
        int[] histogram = new int[256];
        int usablePixelCount = 0;

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (ignoreMask.length == width * height && ignoreMask[row + x]) {
                    continue;
                }
                usablePixelCount++;
                int localMean = localMean(integral, width, height, x, y, backgroundRadius);
                background[row + x] = localMean;
                int value = Math.max(0, gray[row + x] - localMean);
                contrast[row + x] = value;
                histogram[Math.min(255, value)]++;
            }
        }

        int threshold = Math.max(minContrast, percentile(histogram, Math.max(1, usablePixelCount), contrastPercentile));
        boolean[] visited = new boolean[width * height];
        List<StarCandidate> candidates = new ArrayList<>();

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int index = y * width + x;
                if (visited[index]
                        || contrast[index] < threshold
                        || background[index] > maxStarBackground
                        || (ignoreMask.length == width * height && ignoreMask[index])) {
                    continue;
                }

                StarCandidate candidate = componentCandidate(gray, contrast, background, visited, width, height, x, y, threshold);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        candidates = suppressDenseArtifacts(candidates);
        candidates = suppressLinearArtifacts(candidates);
        candidates.sort(Comparator.comparingInt(StarCandidate::brightness).reversed());
        List<PlateSolveStar> stars = new ArrayList<>();
        int minDistanceSq = 36;

        for (StarCandidate candidate : candidates) {
            if (stars.size() >= maxStars) {
                break;
            }

            boolean tooClose = stars.stream().anyMatch(star -> {
                double dx = star.cropX() - candidate.x();
                double dy = star.cropY() - candidate.y();
                return dx * dx + dy * dy < minDistanceSq;
            });

            if (tooClose) {
                continue;
            }

            int id = stars.size() + 1;
            stars.add(new PlateSolveStar(
                    id,
                    crop.x() + candidate.x(),
                    crop.y() + candidate.y(),
                    candidate.x(),
                    candidate.y(),
                    candidate.brightness(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false
            ));
        }

        return stars;
    }

    private List<StarCandidate> suppressLinearArtifacts(List<StarCandidate> candidates) {
        if (candidates.size() < 8) {
            return candidates;
        }

        boolean[] rejected = new boolean[candidates.size()];
        suppressLinearArtifacts(candidates, rejected, true);
        suppressLinearArtifacts(candidates, rejected, false);

        List<StarCandidate> filtered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!rejected[i]) {
                filtered.add(candidates.get(i));
            }
        }

        return filtered;
    }

    private List<StarCandidate> suppressDenseArtifacts(List<StarCandidate> candidates) {
        if (candidates.size() < 8) {
            return candidates;
        }

        double connectDistanceSq = Math.pow(Math.max(24.0, maxStarDiameter * 1.6), 2);
        boolean[] visited = new boolean[candidates.size()];
        boolean[] rejected = new boolean[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            if (visited[i]) {
                continue;
            }

            List<Integer> component = collectCandidateComponent(candidates, visited, i, connectDistanceSq);
            if (isDenseArtifact(candidates, component)) {
                for (int index : component) {
                    rejected[index] = true;
                }
            }
        }

        List<StarCandidate> filtered = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!rejected[i]) {
                filtered.add(candidates.get(i));
            }
        }

        return filtered;
    }

    private List<Integer> collectCandidateComponent(
            List<StarCandidate> candidates,
            boolean[] visited,
            int start,
            double connectDistanceSq) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        List<Integer> component = new ArrayList<>();
        queue.add(start);
        visited[start] = true;

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            component.add(index);
            StarCandidate current = candidates.get(index);

            for (int next = 0; next < candidates.size(); next++) {
                if (visited[next]) {
                    continue;
                }

                StarCandidate candidate = candidates.get(next);
                double dx = current.x() - candidate.x();
                double dy = current.y() - candidate.y();
                if (dx * dx + dy * dy <= connectDistanceSq) {
                    visited[next] = true;
                    queue.add(next);
                }
            }
        }

        return component;
    }

    private boolean isDenseArtifact(List<StarCandidate> candidates, List<Integer> component) {
        if (component.size() < 6) {
            return false;
        }

        double meanX = 0;
        double meanY = 0;
        double meanBackground = 0;
        for (int index : component) {
            StarCandidate candidate = candidates.get(index);
            meanX += candidate.x();
            meanY += candidate.y();
            meanBackground += candidate.background();
        }
        meanX /= component.size();
        meanY /= component.size();
        meanBackground /= component.size();

        double covXX = 0;
        double covXY = 0;
        double covYY = 0;
        double maxDistanceSq = 0;
        double nearestDistanceSum = 0;

        for (int index : component) {
            StarCandidate candidate = candidates.get(index);
            double dx = candidate.x() - meanX;
            double dy = candidate.y() - meanY;
            covXX += dx * dx;
            covXY += dx * dy;
            covYY += dy * dy;
            maxDistanceSq = Math.max(maxDistanceSq, dx * dx + dy * dy);
            nearestDistanceSum += nearestDistance(candidates, component, index);
        }

        covXX /= component.size();
        covXY /= component.size();
        covYY /= component.size();

        double trace = covXX + covYY;
        double determinant = covXX * covYY - covXY * covXY;
        double discriminant = Math.max(0, trace * trace - 4 * determinant);
        double major = (trace + Math.sqrt(discriminant)) / 2.0;
        double minor = Math.max(0.001, (trace - Math.sqrt(discriminant)) / 2.0);
        double elongation = major / minor;
        double span = Math.sqrt(maxDistanceSq) * 2.0;
        double averageNearestDistance = nearestDistanceSum / component.size();

        return span >= Math.max(48.0, maxStarDiameter * 2.6)
                && averageNearestDistance <= Math.max(18.0, maxStarDiameter * 1.1)
                && (meanBackground >= 28 || elongation >= 5.0);
    }

    private double nearestDistance(List<StarCandidate> candidates, List<Integer> component, int index) {
        StarCandidate source = candidates.get(index);
        double nearest = Double.MAX_VALUE;

        for (int otherIndex : component) {
            if (otherIndex == index) {
                continue;
            }

            StarCandidate candidate = candidates.get(otherIndex);
            double dx = source.x() - candidate.x();
            double dy = source.y() - candidate.y();
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy));
        }

        return nearest == Double.MAX_VALUE ? 0 : nearest;
    }

    private void suppressLinearArtifacts(List<StarCandidate> candidates, boolean[] rejected, boolean vertical) {
        double bandWidth = Math.max(10.0, maxStarDiameter * 0.8);
        double maxGap = Math.max(20.0, maxStarDiameter * 1.5);
        double minSpan = Math.max(54.0, maxStarDiameter * 3.0);
        int minRun = 6;

        for (int i = 0; i < candidates.size(); i++) {
            if (rejected[i]) {
                continue;
            }

            StarCandidate anchor = candidates.get(i);
            List<Integer> band = new ArrayList<>();
            for (int j = 0; j < candidates.size(); j++) {
                if (rejected[j]) {
                    continue;
                }

                StarCandidate candidate = candidates.get(j);
                double crossAxisDistance = vertical
                        ? Math.abs(candidate.x() - anchor.x())
                        : Math.abs(candidate.y() - anchor.y());
                if (crossAxisDistance <= bandWidth) {
                    band.add(j);
                }
            }

            if (band.size() < minRun) {
                continue;
            }

            band.sort(Comparator.comparingDouble(index -> vertical
                    ? candidates.get(index).y()
                    : candidates.get(index).x()));

            int runStart = 0;
            for (int runEnd = 1; runEnd <= band.size(); runEnd++) {
                boolean endOfRun = runEnd == band.size();
                if (!endOfRun) {
                    double previous = vertical
                            ? candidates.get(band.get(runEnd - 1)).y()
                            : candidates.get(band.get(runEnd - 1)).x();
                    double current = vertical
                            ? candidates.get(band.get(runEnd)).y()
                            : candidates.get(band.get(runEnd)).x();
                    endOfRun = current - previous > maxGap;
                }

                if (endOfRun) {
                    markLinearRun(candidates, band, rejected, runStart, runEnd, vertical, minRun, minSpan);
                    runStart = runEnd;
                }
            }
        }
    }

    private void markLinearRun(
            List<StarCandidate> candidates,
            List<Integer> band,
            boolean[] rejected,
            int runStart,
            int runEnd,
            boolean vertical,
            int minRun,
            double minSpan) {
        int runLength = runEnd - runStart;
        if (runLength < minRun) {
            return;
        }

        StarCandidate first = candidates.get(band.get(runStart));
        StarCandidate last = candidates.get(band.get(runEnd - 1));
        double span = vertical ? Math.abs(last.y() - first.y()) : Math.abs(last.x() - first.x());
        if (span < minSpan) {
            return;
        }

        for (int i = runStart; i < runEnd; i++) {
            rejected[band.get(i)] = true;
        }
    }

    /**
     * Writes the astrometry-engine config for our runs: the configured index directory instead of
     * the distribution's default path, all indexes loaded at once. Rewritten only when it changes.
     */
    private Path engineConfigFile() throws IOException {
        Path configFile = workDir.resolve("astrometry.cfg");
        String content = String.join("\n",
                "# Generated by CamServer from app.plate-solve.index-dir; edits are overwritten.",
                "add_path " + indexDir,
                "autoindex",
                "inparallel",
                "");
        if (Files.exists(configFile) && content.equals(Files.readString(configFile))) {
            return configFile;
        }
        Files.createDirectories(workDir);
        Path tmp = workDir.resolve("astrometry.cfg.tmp-" + System.nanoTime());
        Files.writeString(tmp, content);
        Files.move(tmp, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return configFile;
    }

    private SolverRun runSolveField(long imgId, Image image, Path croppedPath) throws IOException, InterruptedException {
        return runSolveField(
                imgId,
                image,
                croppedPath,
                "solve",
                scaleLowDeg,
                scaleHighDeg,
                searchRadiusDeg,
                downsample,
                0,
                List.of()
        );
    }

    private SolverRun runSolveField(
            long imgId,
            Image image,
            Path inputPath,
            String outputSubdir,
            double solveScaleLowDeg,
            double solveScaleHighDeg,
            double solveSearchRadiusDeg,
            int solveDownsample,
            int tweakOrder,
            List<String> extraArgs) throws IOException, InterruptedException {
        Path outputDir = workDir.resolve(Long.toString(imgId)).resolve(outputSubdir);
        Files.createDirectories(outputDir);
        Path logPath = outputDir.resolve("solve-field.log");

        List<String> command = new ArrayList<>();
        command.add(resolvedSolverCommand);
        command.add("--overwrite");
        command.add("--no-plots");
        if (indexDir != null) {
            // Older solve-field builds (Ubuntu 18.04 ships 0.73) have no --index-dir option, so
            // point astrometry-engine at the index directory through a generated config instead.
            command.add("--config");
            command.add(engineConfigFile().toString());
        }
        command.add("--dir");
        command.add(outputDir.toString());
        command.add("--cpulimit");
        command.add(Integer.toString(timeoutSeconds));
        command.add("--downsample");
        command.add(Integer.toString(Math.max(1, solveDownsample)));
        command.add("--scale-units");
        command.add("degwidth");
        command.add("--scale-low");
        command.add(Double.toString(solveScaleLowDeg));
        command.add("--scale-high");
        command.add(Double.toString(solveScaleHighDeg));
        if (tweakOrder > 0) {
            command.add("--tweak-order");
            command.add(Integer.toString(tweakOrder));
        }
        command.addAll(extraArgs);
        if (solveSearchRadiusDeg > 0) {
            zenithCoordinate(image)
                    .ifPresent(coordinate -> {
                        command.add("--ra");
                        command.add(Double.toString(coordinate.raDeg()));
                        command.add("--dec");
                        command.add(Double.toString(coordinate.decDeg()));
                        command.add("--radius");
                        command.add(Double.toString(solveSearchRadiusDeg));
                    });
        }
        command.add(inputPath.toString());

        updateProgress(imgId, "Solving WCS", 36, "Launching: " + String.join(" ", command), List.of());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();
        Instant startedAt = Instant.now();
        boolean finished = false;
        while (!finished) {
            finished = process.waitFor(1, TimeUnit.SECONDS);
            long elapsedSeconds = Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
            int percent = 36 + (int) Math.min(42, (elapsedSeconds * 42) / Math.max(1, timeoutSeconds));
            String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
            updateProgress(
                    imgId,
                    "Solving WCS",
                    percent,
                    "Astrometry.net solve-field is running (" + elapsedSeconds + "s / " + timeoutSeconds + "s).",
                    tailLog(log, 12)
            );

            if (!finished && elapsedSeconds > timeoutSeconds + 5L) {
                break;
            }
        }

        if (!finished) {
            process.destroyForcibly();
            String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
            return new SolverRun(false, null, log + "\nsolve-field timed out after " + timeoutSeconds + " seconds.");
        }

        String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
        Path wcsPath = outputDir.resolve(fileStem(inputPath) + ".wcs");
        return new SolverRun(process.exitValue() == 0 && Files.exists(wcsPath), wcsPath, log);
    }

    private SolverRun runFitWcs(
            long imgId,
            Path correspondencesPath,
            int imageWidth,
            int imageHeight) throws IOException, InterruptedException {
        Path outputDir = workDir.resolve(Long.toString(imgId)).resolve("fit-wcs-guided");
        Files.createDirectories(outputDir);
        Path logPath = outputDir.resolve("fit-wcs.log");
        Path wcsPath = outputDir.resolve(fileStem(correspondencesPath) + ".wcs");

        List<String> command = new ArrayList<>();
        command.add(resolvedFitWcsCommand);
        command.add("-c");
        command.add(correspondencesPath.toString());
        command.add("-o");
        command.add(wcsPath.toString());
        command.add("-W");
        command.add(Integer.toString(imageWidth));
        command.add("-H");
        command.add(Integer.toString(imageHeight));

        updateProgress(imgId, "Fitting guided WCS", 36, "Launching: " + String.join(" ", command), List.of());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile())
                .start();
        boolean finished = process.waitFor(Math.min(timeoutSeconds, 30), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }

        String log = Files.exists(logPath) ? Files.readString(logPath, StandardCharsets.UTF_8) : "";
        if (!finished) {
            return new SolverRun(false, null, log + "\nfit-wcs timed out after " + Math.min(timeoutSeconds, 30) + " seconds.");
        }

        return new SolverRun(process.exitValue() == 0 && Files.exists(wcsPath), wcsPath, log);
    }

    private Optional<WcsHeader> parseWcsHeader(Path wcsPath) throws IOException {
        if (wcsPath == null || !Files.exists(wcsPath)) {
            return Optional.empty();
        }

        byte[] bytes = Files.readAllBytes(wcsPath);
        Map<String, String> header = new HashMap<>();

        for (int offset = 0; offset + 80 <= bytes.length; offset += 80) {
            String card = new String(bytes, offset, 80, StandardCharsets.US_ASCII);
            String key = card.substring(0, 8).trim();
            if ("END".equals(key)) {
                break;
            }

            if (card.length() > 10 && card.charAt(8) == '=') {
                String value = card.substring(10).split("/", 2)[0].trim().replace("'", "");
                header.put(key, value);
            }
        }

        Optional<Double> crpix1 = headerDouble(header, "CRPIX1");
        Optional<Double> crpix2 = headerDouble(header, "CRPIX2");
        Optional<Double> crval1 = headerDouble(header, "CRVAL1");
        Optional<Double> crval2 = headerDouble(header, "CRVAL2");

        if (crpix1.isEmpty() || crpix2.isEmpty() || crval1.isEmpty() || crval2.isEmpty()) {
            return Optional.empty();
        }

        double cd11 = headerDouble(header, "CD1_1")
                .orElse(headerDouble(header, "CDELT1").orElse(1.0) * headerDouble(header, "PC1_1").orElse(1.0));
        double cd12 = headerDouble(header, "CD1_2")
                .orElse(headerDouble(header, "CDELT1").orElse(1.0) * headerDouble(header, "PC1_2").orElse(0.0));
        double cd21 = headerDouble(header, "CD2_1")
                .orElse(headerDouble(header, "CDELT2").orElse(1.0) * headerDouble(header, "PC2_1").orElse(0.0));
        double cd22 = headerDouble(header, "CD2_2")
                .orElse(headerDouble(header, "CDELT2").orElse(1.0) * headerDouble(header, "PC2_2").orElse(1.0));
        int width = headerDouble(header, "IMAGEW").map(Double::intValue)
                .orElse(headerDouble(header, "NAXIS1").map(Double::intValue).orElse(0));
        int height = headerDouble(header, "IMAGEH").map(Double::intValue)
                .orElse(headerDouble(header, "NAXIS2").map(Double::intValue).orElse(0));

        return Optional.of(new WcsHeader(
                crpix1.get(),
                crpix2.get(),
                crval1.get(),
                crval2.get(),
                cd11,
                cd12,
                cd21,
                cd22,
                width,
                height
        ));
    }

    private WcsHeader withImageSize(WcsHeader header, int width, int height) {
        return new WcsHeader(
                header.crpix1(),
                header.crpix2(),
                header.crval1(),
                header.crval2(),
                header.cd11(),
                header.cd12(),
                header.cd21(),
                header.cd22(),
                width,
                height
        );
    }

    private PlateSolveSolution parseSolution(SolverRun solverRun, WcsHeader wcsHeader) {
        if (!solverRun.solved()) {
            return unavailableSolution(solverRun.log());
        }

        Double ra = null;
        Double dec = null;
        Double fieldWidth = null;
        Double fieldHeight = null;

        Matcher centerMatcher = FIELD_CENTER_PATTERN.matcher(solverRun.log());
        if (centerMatcher.find()) {
            ra = parseDouble(centerMatcher.group(1)).orElse(null);
            dec = parseDouble(centerMatcher.group(2)).orElse(null);
        }

        Matcher sizeMatcher = FIELD_SIZE_PATTERN.matcher(solverRun.log());
        if (sizeMatcher.find()) {
            fieldWidth = parseDouble(sizeMatcher.group(1)).orElse(null);
            fieldHeight = parseDouble(sizeMatcher.group(2)).orElse(null);
        }

        if (wcsHeader != null) {
            SkyCoordinate center = pixelToSky(wcsHeader.width() / 2.0, wcsHeader.height() / 2.0, wcsHeader);
            SkyCoordinate left = pixelToSky(0, wcsHeader.height() / 2.0, wcsHeader);
            SkyCoordinate right = pixelToSky(wcsHeader.width(), wcsHeader.height() / 2.0, wcsHeader);
            SkyCoordinate top = pixelToSky(wcsHeader.width() / 2.0, 0, wcsHeader);
            SkyCoordinate bottom = pixelToSky(wcsHeader.width() / 2.0, wcsHeader.height(), wcsHeader);
            ra = center.raDeg();
            dec = center.decDeg();
            fieldWidth = angularDistanceDeg(left, right);
            fieldHeight = angularDistanceDeg(top, bottom);
        }

        return new PlateSolveSolution(
                true,
                ra,
                dec,
                fieldWidth,
                fieldHeight,
                siteLatitudeDeg,
                siteLongitudeDeg,
                Objects.toString(solverRun.wcsPath(), null),
                compactSolverLog(solverRun.log())
        );
    }

    private PlateSolveSolution unavailableSolution(String solverLog) {
        return new PlateSolveSolution(
                false,
                null,
                null,
                null,
                null,
                siteLatitudeDeg,
                siteLongitudeDeg,
                null,
                compactSolverLog(solverLog)
        );
    }

    private Optional<PlateSolveResult> completeWithAllSkyFallback(
            long imgId,
            Image image,
            BufferedImage source,
            int geometryThreshold,
            PlateSolveCrop crop,
            List<PlateSolveStar> stars,
            String reason,
            String solverLog) {
        updateProgress(imgId, "Local all-sky WCS", 92, reason, tailLog(solverLog, 12));
        Optional<AllSkySolve> solve = solveAllSky(image, source, geometryThreshold, crop, stars, solverLog);
        if (solve.isEmpty()) {
            return Optional.empty();
        }

        AllSkySolve allSkySolve = solve.get();
        allSkyProjectionCache.put(cameraKey(image), allSkySolve.projection());
        String message = "Fixed-camera all-sky WCS completed locally with "
                + allSkySolve.projection().matchedStars()
                + " catalog anchors.";
        updateProgress(imgId, "Solved", 100, message, tailLog(allSkySolve.solution().solverLog(), 12));
        return Optional.of(complete(
                imgId,
                PlateSolveStatus.SOLVED,
                message,
                crop,
                allSkySolve.solution(),
                allSkySolve.stars()
        ));
    }

    private Optional<PlateSolveResult> completeWithUndistortedAstrometry(
            long imgId,
            Image image,
            BufferedImage source,
            int geometryThreshold,
            PlateSolveCrop crop,
            List<PlateSolveStar> stars,
            String reason,
            String solverLog) throws IOException, InterruptedException {
        updateProgress(imgId, "Fisheye calibration", 30, reason, tailLog(solverLog, 12));
        Optional<AllSkySolve> calibrated = solveAllSky(image, source, geometryThreshold, crop, stars, solverLog);
        if (calibrated.isEmpty()) {
            return Optional.empty();
        }

        AllSkySolve allSkySolve = calibrated.get();
        allSkyProjectionCache.put(cameraKey(image), allSkySolve.projection());
        UndistortedSolveInput undistortedInput = writeUndistortedZenithCutout(
                imgId,
                source,
                allSkySolve.projection()
        );
        Optional<PlateSolveResult> guidedWcsResult = completeWithCatalogGuidedFitWcs(
                imgId,
                image,
                crop,
                allSkySolve,
                undistortedInput
        );
        if (guidedWcsResult.isPresent()) {
            return guidedWcsResult;
        }

        UndistortedSolveInput undistortedXyList = writeUndistortedXyList(
                imgId,
                allSkySolve.stars(),
                allSkySolve.projection(),
                undistortedInput
        );

        updateProgress(
                imgId,
                "Solving curated stars",
                34,
                "Running Astrometry.net on locally filtered star centroids.",
                tailLog(allSkySolve.solution().solverLog(), 12)
        );
        SolverRun solverRun = runSolveField(
                imgId,
                image,
                undistortedXyList.path(),
                "solve-undistorted-xyls",
                UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.82,
                UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.12,
                Math.min(searchRadiusDeg, UNDISTORTED_SOLVE_SEARCH_RADIUS_DEG),
                1,
                UNDISTORTED_SOLVE_TWEAK_ORDER,
                List.of(
                        "--width", Integer.toString(undistortedXyList.width()),
                        "--height", Integer.toString(undistortedXyList.height()),
                        "--x-column", "X",
                        "--y-column", "Y",
                        "--sort-column", "FLUX",
                        "--objs", Integer.toString(UNDISTORTED_XYLIST_MAX_STARS),
                        "--depth", "10,20,30,40,60,80,120,160,200",
                        "--uniformize", "10",
                        "--pixel-error", "8"
                )
        );
        boolean solvedFromXyList = solverRun.solved();
        if (!solverRun.solved()) {
            updateProgress(
                    imgId,
                    "Solving undistorted image",
                    60,
                    "Curated centroid solve did not finish; retrying the undistorted cutout with strict extraction.",
                    tailLog(solverRun.log(), 12)
            );
            solverRun = runSolveField(
                    imgId,
                    image,
                    undistortedInput.path(),
                    "solve-undistorted",
                    UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.82,
                    UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.12,
                    Math.min(searchRadiusDeg, UNDISTORTED_SOLVE_SEARCH_RADIUS_DEG),
                    1,
                    UNDISTORTED_SOLVE_TWEAK_ORDER,
                    List.of(
                            "--objs", "180",
                            "--depth", "10,20,30,40,60,80,120",
                            "--nsigma", "10",
                            "--uniformize", "10",
                            "--pixel-error", "8"
                    )
            );
        }
        WcsHeader wcsHeader = solverRun.solved()
                ? parseWcsHeader(solverRun.wcsPath()).orElse(null)
                : null;
        if (wcsHeader == null) {
            return Optional.empty();
        }

        PlateSolveSolution solution = parseSolution(solverRun, wcsHeader);
        if (!solution.solved()) {
            return Optional.empty();
        }

        if (!isPlausibleUndistortedWcs(image, solution)) {
            return Optional.empty();
        }

        List<PlateSolveStar> identifiedStars = identifyStarsWithUndistortedWcs(
                allSkySolve.stars(),
                allSkySolve.projection(),
                undistortedInput,
                wcsHeader
        );
        if (reliableCatalogMatches(identifiedStars) < MIN_UNDISTORTED_WCS_CATALOG_MATCHES) {
            return Optional.empty();
        }
        String message = solvedFromXyList
                ? "Astrometry.net solved fisheye-corrected zenith cutout from locally filtered star centroids."
                : "Astrometry.net solved fisheye-corrected zenith cutout using strict source extraction.";
        updateProgress(imgId, "Solved", 100, message, tailLog(solverRun.log(), 12));
        return Optional.of(complete(
                imgId,
                PlateSolveStatus.SOLVED,
                message,
                crop,
                solution,
                identifiedStars
        ));
    }

    private Optional<PlateSolveResult> completeWithCatalogGuidedFitWcs(
            long imgId,
            Image image,
            PlateSolveCrop crop,
            AllSkySolve allSkySolve,
            UndistortedSolveInput undistortedInput) throws IOException, InterruptedException {
        if (!isCommandAvailable(resolvedFitWcsCommand)) {
            return Optional.empty();
        }

        Optional<Double> siderealTimeDeg = localSiderealTimeDeg(image);
        if (siderealTimeDeg.isEmpty()) {
            return Optional.empty();
        }

        List<WcsCorrespondence> correspondences = selectUndistortedCorrespondences(siderealTimeDeg.get(), undistortedInput);
        if (correspondences.size() < 8) {
            return Optional.empty();
        }

        Path correspondencesPath = writeUndistortedCorrespondences(imgId, correspondences);
        updateProgress(
                imgId,
                "Fitting guided WCS",
                34,
                "Fitting Astrometry.net WCS from identified local guide stars.",
                tailLog(allSkySolve.solution().solverLog(), 12)
        );
        SolverRun solverRun = runFitWcs(
                imgId,
                correspondencesPath,
                undistortedInput.width(),
                undistortedInput.height()
        );
        WcsHeader wcsHeader = solverRun.solved()
                ? parseWcsHeader(solverRun.wcsPath())
                        .map(header -> withImageSize(header, undistortedInput.width(), undistortedInput.height()))
                        .orElse(null)
                : null;
        if (wcsHeader == null) {
            return Optional.empty();
        }

        PlateSolveSolution solution = parseSolution(solverRun, wcsHeader);
        if (!solution.solved() || !isPlausibleUndistortedWcs(image, solution)) {
            return Optional.empty();
        }

        List<PlateSolveStar> identifiedStars = identifyStarsForGuidedWcs(
                allSkySolve.stars(),
                allSkySolve.projection(),
                undistortedInput,
                wcsHeader,
                siderealTimeDeg.get()
        );
        if (reliableCatalogMatches(identifiedStars) < MIN_UNDISTORTED_WCS_CATALOG_MATCHES) {
            return Optional.empty();
        }

        String message = "Astrometry.net fit-wcs solved the fisheye-corrected frame from identified local guide stars.";
        updateProgress(imgId, "Solved", 100, message, tailLog(solverRun.log(), 12));
        return Optional.of(complete(
                imgId,
                PlateSolveStatus.SOLVED,
                message,
                crop,
                solution,
                identifiedStars
        ));
    }

    private boolean isPlausibleUndistortedWcs(Image image, PlateSolveSolution solution) {
        if (solution.fieldCenterRaDeg() == null
                || solution.fieldCenterDecDeg() == null
                || solution.fieldWidthDeg() == null
                || solution.fieldHeightDeg() == null) {
            return false;
        }

        if (solution.fieldWidthDeg() < UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.8
                || solution.fieldWidthDeg() > UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.2
                || solution.fieldHeightDeg() < UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 0.8
                || solution.fieldHeightDeg() > UNDISTORTED_SOLVE_FIELD_WIDTH_DEG * 1.2) {
            return false;
        }

        Optional<SkyCoordinate> expectedCenter = zenithCoordinate(image);
        if (expectedCenter.isEmpty()) {
            return true;
        }

        double centerErrorDeg = angularDistanceDeg(
                solution.fieldCenterRaDeg(),
                solution.fieldCenterDecDeg(),
                expectedCenter.get().raDeg(),
                expectedCenter.get().decDeg()
        );
        return centerErrorDeg <= 15.0;
    }

    private long reliableCatalogMatches(List<PlateSolveStar> stars) {
        double maxErrorArcsec = catalogMatchRadiusDeg * 3600.0;
        return stars.stream()
                .filter(star -> star.catalogMatchDistanceArcsec() != null
                        && star.catalogMatchDistanceArcsec() <= maxErrorArcsec)
                .count();
    }

    private Optional<AllSkySolve> solveAllSky(
            Image image,
            BufferedImage source,
            int geometryThreshold,
            PlateSolveCrop crop,
            List<PlateSolveStar> stars,
            String solverLog) {
        Optional<Double> siderealTimeDeg = localSiderealTimeDeg(image);
        if (siderealTimeDeg.isEmpty() || stars.size() < 3) {
            return Optional.empty();
        }

        List<CatalogStar> catalog = getCatalogStars();
        if (catalog.isEmpty()) {
            return Optional.empty();
        }

        AllSkyGeometry geometry = estimateAllSkyGeometry(source, crop, geometryThreshold);
        AllSkyProjection cachedProjection = allSkyProjectionCache.get(cameraKey(image));
        AllSkyProjection projection = cachedProjection != null
                && cachedProjection.originalWidth() == crop.originalWidth()
                && cachedProjection.originalHeight() == crop.originalHeight()
                ? cachedProjection
                : fitAllSkyProjection(catalog, stars, geometry, crop, siderealTimeDeg.get()).orElse(null);
        if (projection == null) {
            return Optional.empty();
        }

        List<PlateSolveStar> identifiedStars = identifyStarsWithAllSky(stars, projection, siderealTimeDeg.get());
        SkyCoordinate center = new SkyCoordinate(siderealTimeDeg.get(), siteLatitudeDeg);
        String log = appendSolverLog(
                solverLog,
                "Fixed-camera all-sky WCS fallback solved with "
                        + projection.matchedStars()
                        + " catalog anchors; RMS "
                        + String.format("%.1f", projection.rmsErrorPx())
                        + " px; rotation "
                        + String.format("%.1f", projection.rotationDeg())
                        + " deg; fisheye radial power "
                        + String.format("%.2f", projection.radialPower())
                        + "."
        );
        PlateSolveSolution solution = new PlateSolveSolution(
                true,
                center.raDeg(),
                center.decDeg(),
                180.0,
                180.0,
                siteLatitudeDeg,
                siteLongitudeDeg,
                null,
                log
        );
        return Optional.of(new AllSkySolve(projection, solution, identifiedStars));
    }

    private Optional<AllSkyProjection> fitAllSkyProjection(
            List<CatalogStar> catalog,
            List<PlateSolveStar> stars,
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            double siderealTimeDeg) {
        List<PlateSolveStar> candidates = stars.stream()
                .filter(star -> distancePx(star.x(), star.y(), geometry.centerX(), geometry.centerY())
                        <= geometry.radius() * 0.92)
                .sorted(Comparator.comparingInt(PlateSolveStar::brightness).reversed())
                .limit(260)
                .toList();
        if (candidates.size() < 3) {
            return Optional.empty();
        }

        List<HorizontalCatalogStar> visibleStars = visibleCatalogStars(catalog, siderealTimeDeg).stream()
                .filter(star -> star.altitudeDeg() >= 18.0)
                .filter(star -> star.catalogStar().magnitude() == null || star.catalogStar().magnitude() <= 3.5)
                .limit(80)
                .toList();
        if (visibleStars.size() < 3) {
            return Optional.empty();
        }

        ProjectionScore best = null;
        for (double rotation = 0; rotation < 360; rotation += 2.0) {
            for (double radialPower : fisheyeRadialPowers(crop)) {
                ProjectionScore score = scoreProjection(geometry, crop, candidates, visibleStars, rotation, radialPower);
                if (best == null || score.score() > best.score()) {
                    best = score;
                }
            }
        }

        if (best != null) {
            double baseRotation = best.projection().rotationDeg();
            double baseRadialPower = best.projection().radialPower();
            for (double rotation = baseRotation - 2.0; rotation <= baseRotation + 2.0; rotation += 0.25) {
                for (double radialPower = baseRadialPower - 0.08; radialPower <= baseRadialPower + 0.08; radialPower += 0.02) {
                    ProjectionScore score = scoreProjection(
                            geometry,
                            crop,
                            candidates,
                            visibleStars,
                            normalizeDegrees(rotation),
                            clampRadialPower(radialPower)
                    );
                    if (score.score() > best.score()) {
                        best = score;
                    }
                }
            }
        }

        if (best == null || best.projection().matchedStars() < 3) {
            return Optional.empty();
        }

        double tolerancePx = allSkyMatchTolerancePx(geometry);
        if (best.projection().rmsErrorPx() > tolerancePx * 0.9) {
            return Optional.empty();
        }

        return Optional.of(best.projection());
    }

    private ProjectionScore scoreProjection(
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            List<PlateSolveStar> candidates,
            List<HorizontalCatalogStar> visibleStars,
            double rotationDeg,
            double radialPower) {
        double tolerancePx = allSkyMatchTolerancePx(geometry);
        boolean[] used = new boolean[candidates.size()];
        int matches = 0;
        double weightedScore = 0;
        double errorSq = 0;

        for (HorizontalCatalogStar catalogStar : visibleStars) {
            Optional<ProjectedPoint> projected = projectHorizontal(
                    geometry,
                    crop,
                    catalogStar.altitudeDeg(),
                    catalogStar.azimuthDeg(),
                    rotationDeg,
                    radialPower
            );
            if (projected.isEmpty()) {
                continue;
            }

            int bestIndex = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                if (used[i]) {
                    continue;
                }

                PlateSolveStar candidate = candidates.get(i);
                double distance = distancePx(candidate.x(), candidate.y(), projected.get().x(), projected.get().y());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0 && bestDistance <= tolerancePx) {
                used[bestIndex] = true;
                matches++;
                errorSq += bestDistance * bestDistance;
                double magnitude = catalogStar.catalogStar().magnitude() == null
                        ? 3.0
                        : catalogStar.catalogStar().magnitude();
                weightedScore += Math.max(0.5, 5.0 - magnitude) * (1.0 - bestDistance / tolerancePx);
            }
        }

        double rms = matches == 0 ? Double.MAX_VALUE : Math.sqrt(errorSq / matches);
        AllSkyProjection projection = new AllSkyProjection(
                geometry.centerX(),
                geometry.centerY(),
                geometry.radius(),
                normalizeDegrees(rotationDeg),
                radialPower,
                crop.originalWidth(),
                crop.originalHeight(),
                matches,
                rms
        );
        return new ProjectionScore(projection, matches * 1000.0 + weightedScore * 100.0 - rms * 10.0);
    }

    private List<HorizontalCatalogStar> visibleCatalogStars(List<CatalogStar> catalog, double siderealTimeDeg) {
        List<HorizontalCatalogStar> visible = new ArrayList<>();
        for (CatalogStar star : catalog) {
            HorizontalCoordinate coordinate = skyToHorizontal(star.raDeg(), star.decDeg(), siderealTimeDeg);
            if (coordinate.altitudeDeg() > 0) {
                visible.add(new HorizontalCatalogStar(star, coordinate.altitudeDeg(), coordinate.azimuthDeg()));
            }
        }

        visible.sort(Comparator.comparingDouble(star -> star.catalogStar().magnitude() == null
                ? 99.0
                : star.catalogStar().magnitude()));
        return visible;
    }

    private List<PlateSolveStar> identifyStarsWithAllSky(
            List<PlateSolveStar> stars,
            AllSkyProjection projection,
            double siderealTimeDeg) {
        return stars.stream()
                .map(star -> {
                    Optional<HorizontalCoordinate> horizontal = pixelToHorizontal(star.x(), star.y(), projection);
                    if (horizontal.isEmpty()) {
                        return star;
                    }

                    SkyCoordinate coordinate = horizontalToSky(horizontal.get(), siderealTimeDeg);
                    Optional<CatalogMatch> match = matchCatalog(coordinate, allSkyCatalogMatchRadiusDeg);
                    return new PlateSolveStar(
                            star.id(),
                            star.x(),
                            star.y(),
                            star.cropX(),
                            star.cropY(),
                            star.brightness(),
                            coordinate.raDeg(),
                            coordinate.decDeg(),
                            match.map(CatalogMatch::name).orElse(null),
                            match.map(CatalogMatch::magnitude).orElse(null),
                            match.map(CatalogMatch::distanceArcsec).orElse(null),
                            match.map(CatalogMatch::identifiers).orElse(List.of()),
                            match.map(CatalogMatch::links).orElseGet(() -> coordinateLinks(coordinate)),
                            true
                    );
                })
                .toList();
    }

    private List<PlateSolveStar> identifyStarsWithUndistortedWcs(
            List<PlateSolveStar> allSkyStars,
            AllSkyProjection projection,
            UndistortedSolveInput undistortedInput,
            WcsHeader wcsHeader) {
        return allSkyStars.stream()
                .map(star -> {
                    Optional<HorizontalCoordinate> horizontal = pixelToHorizontal(star.x(), star.y(), projection);
                    Optional<ProjectedPoint> undistortedPixel = horizontal
                            .flatMap(coordinate -> horizontalToUndistortedPixel(coordinate, undistortedInput));
                    if (undistortedPixel.isEmpty()) {
                        return star;
                    }

                    SkyCoordinate coordinate = pixelToSky(
                            undistortedPixel.get().x(),
                            undistortedPixel.get().y(),
                            wcsHeader
                    );
                    Optional<CatalogMatch> match = matchCatalog(coordinate);
                    return new PlateSolveStar(
                            star.id(),
                            star.x(),
                            star.y(),
                            star.cropX(),
                            star.cropY(),
                            star.brightness(),
                            coordinate.raDeg(),
                            coordinate.decDeg(),
                            match.map(CatalogMatch::name).orElse(null),
                            match.map(CatalogMatch::magnitude).orElse(null),
                            match.map(CatalogMatch::distanceArcsec).orElse(null),
                            match.map(CatalogMatch::identifiers).orElse(List.of()),
                            match.map(CatalogMatch::links).orElseGet(() -> coordinateLinks(coordinate)),
                            true
                    );
                })
                .map(this::stripUnreliableAllSkyCatalogMatch)
                .toList();
    }

    private List<PlateSolveStar> identifyStarsForGuidedWcs(
            List<PlateSolveStar> allSkyStars,
            AllSkyProjection projection,
            UndistortedSolveInput undistortedInput,
            WcsHeader wcsHeader,
            double siderealTimeDeg) {
        List<SnappedCatalogStar> snappedCatalogStars = snapCatalogStarsToDetections(
                allSkyStars,
                projection,
                siderealTimeDeg
        );
        Set<Integer> matchedDetectionIds = snappedCatalogStars.stream()
                .map(SnappedCatalogStar::sourceDetectionId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        List<PlateSolveStar> detectedCandidates = identifyStarsWithUndistortedWcs(
                allSkyStars,
                projection,
                undistortedInput,
                wcsHeader
        ).stream()
                .filter(star -> !matchedDetectionIds.contains(star.id()))
                .map(this::stripCatalogIdentity)
                .toList();

        List<PlateSolveStar> stars = new ArrayList<>(snappedCatalogStars.size() + detectedCandidates.size());
        int nextId = 1;
        for (SnappedCatalogStar snappedStar : snappedCatalogStars) {
            stars.add(withStarId(snappedStar.star(), nextId++));
        }
        for (PlateSolveStar candidate : detectedCandidates) {
            stars.add(withStarId(candidate, nextId++));
        }
        return stars;
    }

    private List<SnappedCatalogStar> snapCatalogStarsToDetections(
            List<PlateSolveStar> detections,
            AllSkyProjection projection,
            double siderealTimeDeg) {
        PlateSolveCrop fullFrame = new PlateSolveCrop(
                0,
                0,
                projection.originalWidth(),
                projection.originalHeight(),
                projection.originalWidth(),
                projection.originalHeight()
        );
        AllSkyGeometry geometry = new AllSkyGeometry(projection.centerX(), projection.centerY(), projection.radius());
        List<SnappedCatalogStar> snappedStars = new ArrayList<>();
        Set<Integer> usedDetections = new HashSet<>();
        Map<String, Boolean> seen = new HashMap<>();
        double snapTolerancePx = catalogSnapTolerancePx(projection);

        for (HorizontalCatalogStar visibleStar : visibleCatalogStars(getCatalogStars(), siderealTimeDeg)) {
            if (snappedStars.size() >= GUIDED_WCS_MAX_CATALOG_MARKERS) {
                break;
            }

            CatalogStar star = visibleStar.catalogStar();
            if (visibleStar.altitudeDeg() < UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG
                    || (star.magnitude() != null && star.magnitude() > GUIDED_WCS_CATALOG_MARKER_MAG_LIMIT)) {
                continue;
            }

            String key = catalogSkyKey(star);
            if (seen.putIfAbsent(key, true) != null) {
                continue;
            }

            Optional<ProjectedPoint> projected = projectHorizontal(
                    geometry,
                    fullFrame,
                    visibleStar.altitudeDeg(),
                    visibleStar.azimuthDeg(),
                    projection.rotationDeg(),
                    projection.radialPower()
            );
            if (projected.isEmpty()) {
                continue;
            }

            Optional<PlateSolveStar> detection = nearestUnusedDetection(
                    detections,
                    usedDetections,
                    projected.get(),
                    snapTolerancePx
            );
            if (detection.isEmpty()) {
                continue;
            }

            usedDetections.add(detection.get().id());
            snappedStars.add(new SnappedCatalogStar(new PlateSolveStar(
                    detection.get().id(),
                    detection.get().x(),
                    detection.get().y(),
                    detection.get().cropX(),
                    detection.get().cropY(),
                    detection.get().brightness(),
                    star.raDeg(),
                    star.decDeg(),
                    star.name(),
                    star.magnitude(),
                    0.0,
                    starIdentifiers(star),
                    starLinks(star),
                    true
            ), detection.get().id()));
        }

        return snappedStars;
    }

    private Optional<PlateSolveStar> nearestUnusedDetection(
            List<PlateSolveStar> detections,
            Set<Integer> usedDetections,
            ProjectedPoint projected,
            double snapTolerancePx) {
        PlateSolveStar best = null;
        double bestDistance = Double.MAX_VALUE;

        for (PlateSolveStar detection : detections) {
            if (usedDetections.contains(detection.id())) {
                continue;
            }

            double distance = distancePx(detection.x(), detection.y(), projected.x(), projected.y());
            if (distance > snapTolerancePx) {
                continue;
            }

            if (best == null || distance < bestDistance) {
                best = detection;
                bestDistance = distance;
            }
        }

        return Optional.ofNullable(best);
    }

    private double catalogSnapTolerancePx(AllSkyProjection projection) {
        return Math.max(
                GUIDED_WCS_CATALOG_SNAP_MIN_PX,
                Math.min(GUIDED_WCS_CATALOG_SNAP_MAX_PX, projection.radius() * GUIDED_WCS_CATALOG_SNAP_FRACTION)
        );
    }

    private PlateSolveStar stripCatalogIdentity(PlateSolveStar star) {
        SkyCoordinate coordinate = star.raDeg() != null && star.decDeg() != null
                ? new SkyCoordinate(star.raDeg(), star.decDeg())
                : null;
        return new PlateSolveStar(
                star.id(),
                star.x(),
                star.y(),
                star.cropX(),
                star.cropY(),
                star.brightness(),
                star.raDeg(),
                star.decDeg(),
                null,
                null,
                null,
                List.of(),
                coordinate == null ? List.of() : coordinateLinks(coordinate),
                star.skyCoordinateSolved()
        );
    }

    private PlateSolveStar withStarId(PlateSolveStar star, int id) {
        return new PlateSolveStar(
                id,
                star.x(),
                star.y(),
                star.cropX(),
                star.cropY(),
                star.brightness(),
                star.raDeg(),
                star.decDeg(),
                star.name(),
                star.magnitude(),
                star.catalogMatchDistanceArcsec(),
                star.identifiers(),
                star.links(),
                star.skyCoordinateSolved()
        );
    }

    private int brightnessForCatalogStar(CatalogStar star) {
        double magnitude = star.magnitude() == null ? GUIDED_WCS_CATALOG_MARKER_MAG_LIMIT : star.magnitude();
        double normalized = Math.max(0.0, Math.min(1.0, (magnitude + 1.5) / (GUIDED_WCS_CATALOG_MARKER_MAG_LIMIT + 1.5)));
        return (int) Math.round(255.0 - normalized * 120.0);
    }

    private String catalogSkyKey(CatalogStar star) {
        return Math.round(star.raDeg() * 1000.0) + ":" + Math.round(star.decDeg() * 1000.0);
    }

    private PlateSolveStar stripUnreliableAllSkyCatalogMatch(PlateSolveStar star) {
        if (star.catalogMatchDistanceArcsec() == null
                || star.catalogMatchDistanceArcsec() <= catalogMatchRadiusDeg * 3600.0) {
            return star;
        }

        SkyCoordinate coordinate = star.raDeg() != null && star.decDeg() != null
                ? new SkyCoordinate(star.raDeg(), star.decDeg())
                : null;
        return new PlateSolveStar(
                star.id(),
                star.x(),
                star.y(),
                star.cropX(),
                star.cropY(),
                star.brightness(),
                star.raDeg(),
                star.decDeg(),
                null,
                null,
                null,
                List.of(),
                coordinate == null ? List.of() : coordinateLinks(coordinate),
                star.skyCoordinateSolved()
        );
    }

    private AllSkyGeometry estimateAllSkyGeometry(BufferedImage source, PlateSolveCrop crop, int threshold) {
        int step = Math.max(1, Math.min(source.getWidth(), source.getHeight()) / 900);
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < source.getHeight(); y += step) {
            for (int x = 0; x < source.getWidth(); x += step) {
                if (luminance(source.getRGB(x, y)) > threshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            double centerX = crop.x() + crop.width() / 2.0;
            double centerY = crop.y() + crop.height() / 2.0;
            return new AllSkyGeometry(centerX, centerY, Math.min(crop.width(), crop.height()) / 2.0);
        }

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double radius = Math.min(maxX - minX + step, maxY - minY + step) / 2.0;
        return new AllSkyGeometry(centerX, centerY, Math.max(1.0, radius));
    }

    private Optional<ProjectedPoint> projectHorizontal(
            AllSkyGeometry geometry,
            PlateSolveCrop crop,
            double altitudeDeg,
            double azimuthDeg,
            double rotationDeg,
            double radialPower) {
        double zenithFraction = (90.0 - altitudeDeg) / 90.0;
        double radialFraction = zenithToFisheyeRadiusFraction(zenithFraction, radialPower);
        if (radialFraction < 0 || radialFraction > 0.94) {
            return Optional.empty();
        }

        double radius = geometry.radius() * radialFraction;
        double angleRad = (azimuthDeg + rotationDeg) * DEG_TO_RAD;
        double x = geometry.centerX() + radius * Math.sin(angleRad);
        double y = geometry.centerY() - radius * Math.cos(angleRad);
        if (x < crop.x() || x > crop.x() + crop.width() || y < crop.y() || y > crop.y() + crop.height()) {
            return Optional.empty();
        }

        return Optional.of(new ProjectedPoint(x, y));
    }

    private UndistortedSolveInput writeUndistortedZenithCutout(
            long imgId,
            BufferedImage source,
            AllSkyProjection projection) throws IOException {
        int size = UNDISTORTED_SOLVE_SIZE_PX;
        double fieldWidthDeg = UNDISTORTED_SOLVE_FIELD_WIDTH_DEG;
        BufferedImage undistorted = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        PlateSolveCrop sourceBounds = new PlateSolveCrop(0, 0, source.getWidth(), source.getHeight(), source.getWidth(), source.getHeight());
        UndistortedSolveInput input = new UndistortedSolveInput(null, size, size, fieldWidthDeg);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Optional<HorizontalCoordinate> horizontal = undistortedPixelToHorizontal(x, y, input);
                if (horizontal.isEmpty() || horizontal.get().altitudeDeg() < 18.0) {
                    undistorted.setRGB(x, y, 0);
                    continue;
                }

                Optional<ProjectedPoint> sourcePoint = projectHorizontal(
                        new AllSkyGeometry(projection.centerX(), projection.centerY(), projection.radius()),
                        sourceBounds,
                        horizontal.get().altitudeDeg(),
                        horizontal.get().azimuthDeg(),
                        projection.rotationDeg(),
                        projection.radialPower()
                );
                undistorted.setRGB(
                        x,
                        y,
                        sourcePoint.map(point -> sampleRgb(source, point.x(), point.y())).orElse(0)
                );
            }
        }

        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path path = imageWorkDir.resolve("undistorted-zenith.jpg");
        ImageIO.write(undistorted, "jpg", path.toFile());
        return new UndistortedSolveInput(path, size, size, fieldWidthDeg);
    }

    private UndistortedSolveInput writeUndistortedXyList(
            long imgId,
            List<PlateSolveStar> stars,
            AllSkyProjection projection,
            UndistortedSolveInput undistortedInput) throws IOException {
        List<XySource> sources = selectUndistortedSources(stars, projection, undistortedInput);
        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path path = imageWorkDir.resolve("undistorted-stars.xyls");
        writeXyListFits(path, sources);
        return new UndistortedSolveInput(
                path,
                undistortedInput.width(),
                undistortedInput.height(),
                undistortedInput.fieldWidthDeg()
        );
    }

    private Path writeUndistortedCorrespondences(
            long imgId,
            List<WcsCorrespondence> correspondences) throws IOException {
        Path imageWorkDir = workDir.resolve(Long.toString(imgId));
        Files.createDirectories(imageWorkDir);
        Path path = imageWorkDir.resolve("undistorted-guide-correspondences.fits");
        writeCorrespondenceFits(path, correspondences);
        return path;
    }

    private List<WcsCorrespondence> selectUndistortedCorrespondences(
            double siderealTimeDeg,
            UndistortedSolveInput undistortedInput) {
        List<CatalogStar> catalog = getCatalogStars();
        if (catalog.isEmpty()) {
            return List.of();
        }

        int[] cellCounts = new int[UNDISTORTED_XYLIST_GRID * UNDISTORTED_XYLIST_GRID];
        List<WcsCorrespondence> correspondences = new ArrayList<>();

        List<HorizontalCatalogStar> visibleStars = visibleCatalogStars(catalog, siderealTimeDeg).stream()
                .filter(star -> star.altitudeDeg() >= UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG)
                .filter(star -> star.catalogStar().magnitude() == null || star.catalogStar().magnitude() <= 7.0)
                .limit(260)
                .toList();

        for (HorizontalCatalogStar catalogStar : visibleStars) {
            if (correspondences.size() >= UNDISTORTED_XYLIST_MAX_STARS) {
                break;
            }

            Optional<ProjectedPoint> undistortedPoint = horizontalToUndistortedPixel(
                    new HorizontalCoordinate(catalogStar.altitudeDeg(), catalogStar.azimuthDeg()),
                    undistortedInput
            );
            if (undistortedPoint.isEmpty()) {
                continue;
            }

            double x = undistortedPoint.get().x();
            double y = undistortedPoint.get().y();
            if (x < UNDISTORTED_XYLIST_MARGIN_PX
                    || x > undistortedInput.width() - UNDISTORTED_XYLIST_MARGIN_PX
                    || y < UNDISTORTED_XYLIST_MARGIN_PX
                    || y > undistortedInput.height() - UNDISTORTED_XYLIST_MARGIN_PX) {
                continue;
            }

            int cellX = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (x / undistortedInput.width() * UNDISTORTED_XYLIST_GRID));
            int cellY = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (y / undistortedInput.height() * UNDISTORTED_XYLIST_GRID));
            int cellIndex = cellY * UNDISTORTED_XYLIST_GRID + cellX;
            if (cellCounts[cellIndex] >= UNDISTORTED_XYLIST_MAX_PER_CELL) {
                continue;
            }

            cellCounts[cellIndex]++;
            CatalogStar star = catalogStar.catalogStar();
            correspondences.add(new WcsCorrespondence(
                    x + 1.0,
                    y + 1.0,
                    star.raDeg(),
                    star.decDeg()
            ));
        }

        return correspondences;
    }

    private List<XySource> selectUndistortedSources(
            List<PlateSolveStar> stars,
            AllSkyProjection projection,
            UndistortedSolveInput undistortedInput) {
        int[] cellCounts = new int[UNDISTORTED_XYLIST_GRID * UNDISTORTED_XYLIST_GRID];
        List<XySource> sources = new ArrayList<>();
        double minDistanceSq = UNDISTORTED_XYLIST_MIN_DISTANCE_PX * UNDISTORTED_XYLIST_MIN_DISTANCE_PX;

        List<PlateSolveStar> sortedStars = stars.stream()
                .sorted(Comparator.comparingInt(PlateSolveStar::brightness).reversed())
                .toList();
        for (PlateSolveStar star : sortedStars) {
            if (sources.size() >= UNDISTORTED_XYLIST_MAX_STARS) {
                break;
            }

            Optional<HorizontalCoordinate> horizontal = pixelToHorizontal(star.x(), star.y(), projection);
            if (horizontal.isEmpty() || horizontal.get().altitudeDeg() < UNDISTORTED_XYLIST_MIN_ALTITUDE_DEG) {
                continue;
            }

            Optional<ProjectedPoint> point = horizontalToUndistortedPixel(horizontal.get(), undistortedInput);
            if (point.isEmpty()) {
                continue;
            }

            double x = point.get().x();
            double y = point.get().y();
            if (x < UNDISTORTED_XYLIST_MARGIN_PX
                    || x > undistortedInput.width() - UNDISTORTED_XYLIST_MARGIN_PX
                    || y < UNDISTORTED_XYLIST_MARGIN_PX
                    || y > undistortedInput.height() - UNDISTORTED_XYLIST_MARGIN_PX) {
                continue;
            }

            int cellX = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (x / undistortedInput.width() * UNDISTORTED_XYLIST_GRID));
            int cellY = Math.min(UNDISTORTED_XYLIST_GRID - 1, (int) (y / undistortedInput.height() * UNDISTORTED_XYLIST_GRID));
            int cellIndex = cellY * UNDISTORTED_XYLIST_GRID + cellX;
            if (cellCounts[cellIndex] >= UNDISTORTED_XYLIST_MAX_PER_CELL) {
                continue;
            }

            boolean tooClose = sources.stream().anyMatch(source -> {
                double dx = source.x() - (x + 1.0);
                double dy = source.y() - (y + 1.0);
                return dx * dx + dy * dy < minDistanceSq;
            });
            if (tooClose) {
                continue;
            }

            sources.add(new XySource(x + 1.0, y + 1.0, Math.max(1.0, star.brightness())));
            cellCounts[cellIndex]++;
        }

        return sources;
    }

    private void writeXyListFits(Path path, List<XySource> sources) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFitsHeader(output, List.of(
                fitsCard("SIMPLE", "T"),
                fitsCard("BITPIX", "8"),
                fitsCard("NAXIS", "0"),
                fitsCard("EXTEND", "T")
        ));
        writeFitsHeader(output, List.of(
                fitsCard("XTENSION", fitsString("BINTABLE")),
                fitsCard("BITPIX", "8"),
                fitsCard("NAXIS", "2"),
                fitsCard("NAXIS1", "12"),
                fitsCard("NAXIS2", Integer.toString(sources.size())),
                fitsCard("PCOUNT", "0"),
                fitsCard("GCOUNT", "1"),
                fitsCard("TFIELDS", "3"),
                fitsCard("TTYPE1", fitsString("X")),
                fitsCard("TFORM1", fitsString("E")),
                fitsCard("TTYPE2", fitsString("Y")),
                fitsCard("TFORM2", fitsString("E")),
                fitsCard("TTYPE3", fitsString("FLUX")),
                fitsCard("TFORM3", fitsString("E")),
                fitsCard("EXTNAME", fitsString("OBJECTS"))
        ));

        ByteBuffer rows = ByteBuffer.allocate(sources.size() * 12);
        for (XySource source : sources) {
            rows.putFloat((float) source.x());
            rows.putFloat((float) source.y());
            rows.putFloat((float) source.flux());
        }
        byte[] rowBytes = rows.array();
        output.write(rowBytes);
        writeFitsPadding(output, rowBytes.length);
        Files.write(path, output.toByteArray());
    }

    private void writeCorrespondenceFits(Path path, List<WcsCorrespondence> correspondences) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeFitsHeader(output, List.of(
                fitsCard("SIMPLE", "T"),
                fitsCard("BITPIX", "8"),
                fitsCard("NAXIS", "0"),
                fitsCard("EXTEND", "T")
        ));
        writeFitsHeader(output, List.of(
                fitsCard("XTENSION", fitsString("BINTABLE")),
                fitsCard("BITPIX", "8"),
                fitsCard("NAXIS", "2"),
                fitsCard("NAXIS1", "16"),
                fitsCard("NAXIS2", Integer.toString(correspondences.size())),
                fitsCard("PCOUNT", "0"),
                fitsCard("GCOUNT", "1"),
                fitsCard("TFIELDS", "4"),
                fitsCard("TTYPE1", fitsString("FIELD_X")),
                fitsCard("TFORM1", fitsString("E")),
                fitsCard("TTYPE2", fitsString("FIELD_Y")),
                fitsCard("TFORM2", fitsString("E")),
                fitsCard("TTYPE3", fitsString("INDEX_RA")),
                fitsCard("TFORM3", fitsString("E")),
                fitsCard("TTYPE4", fitsString("INDEX_DEC")),
                fitsCard("TFORM4", fitsString("E")),
                fitsCard("EXTNAME", fitsString("CORR"))
        ));

        ByteBuffer rows = ByteBuffer.allocate(correspondences.size() * 16);
        for (WcsCorrespondence correspondence : correspondences) {
            rows.putFloat((float) correspondence.fieldX());
            rows.putFloat((float) correspondence.fieldY());
            rows.putFloat((float) correspondence.raDeg());
            rows.putFloat((float) correspondence.decDeg());
        }
        byte[] rowBytes = rows.array();
        output.write(rowBytes);
        writeFitsPadding(output, rowBytes.length);
        Files.write(path, output.toByteArray());
    }

    private void writeFitsHeader(ByteArrayOutputStream output, List<String> cards) {
        for (String card : cards) {
            output.writeBytes(card.getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(padFitsCard("END").getBytes(StandardCharsets.US_ASCII));
        int headerBytes = (cards.size() + 1) * 80;
        writeFitsPadding(output, headerBytes);
    }

    private String fitsCard(String key, String value) {
        String format = value.startsWith("'") ? "%-8s= %-20s" : "%-8s= %20s";
        return padFitsCard(String.format(Locale.ROOT, format, key, value));
    }

    private String fitsString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String padFitsCard(String value) {
        if (value.length() >= 80) {
            return value.substring(0, 80);
        }
        return value + " ".repeat(80 - value.length());
    }

    private void writeFitsPadding(ByteArrayOutputStream output, int bytesWrittenInBlock) {
        int padding = (FITS_BLOCK_SIZE - (bytesWrittenInBlock % FITS_BLOCK_SIZE)) % FITS_BLOCK_SIZE;
        if (padding > 0) {
            output.writeBytes(new byte[padding]);
        }
    }

    private Optional<HorizontalCoordinate> undistortedPixelToHorizontal(
            double x,
            double y,
            UndistortedSolveInput input) {
        double centerX = (input.width() - 1) / 2.0;
        double centerY = (input.height() - 1) / 2.0;
        double halfPlane = Math.tan(input.fieldWidthDeg() * DEG_TO_RAD / 2.0);
        double planeX = (x - centerX) / centerX * halfPlane;
        double planeY = (centerY - y) / centerY * halfPlane;
        double radius = Math.sqrt(planeX * planeX + planeY * planeY);
        double zenithAngleDeg = Math.atan(radius) * RAD_TO_DEG;
        if (zenithAngleDeg >= 90.0) {
            return Optional.empty();
        }

        double altitudeDeg = 90.0 - zenithAngleDeg;
        double azimuthDeg = normalizeDegrees(Math.atan2(planeX, planeY) * RAD_TO_DEG);
        return Optional.of(new HorizontalCoordinate(altitudeDeg, azimuthDeg));
    }

    private Optional<ProjectedPoint> horizontalToUndistortedPixel(
            HorizontalCoordinate coordinate,
            UndistortedSolveInput input) {
        double zenithAngleDeg = 90.0 - coordinate.altitudeDeg();
        if (zenithAngleDeg < 0 || zenithAngleDeg >= 90.0) {
            return Optional.empty();
        }

        double centerX = (input.width() - 1) / 2.0;
        double centerY = (input.height() - 1) / 2.0;
        double halfPlane = Math.tan(input.fieldWidthDeg() * DEG_TO_RAD / 2.0);
        double radius = Math.tan(zenithAngleDeg * DEG_TO_RAD);
        double azimuthRad = coordinate.azimuthDeg() * DEG_TO_RAD;
        double x = centerX + (radius * Math.sin(azimuthRad) / halfPlane) * centerX;
        double y = centerY - (radius * Math.cos(azimuthRad) / halfPlane) * centerY;
        if (x < 0 || x >= input.width() || y < 0 || y >= input.height()) {
            return Optional.empty();
        }

        return Optional.of(new ProjectedPoint(x, y));
    }

    private Optional<HorizontalCoordinate> pixelToHorizontal(double x, double y, AllSkyProjection projection) {
        double dx = x - projection.centerX();
        double dy = projection.centerY() - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        double radialFraction = distance / projection.radius();
        if (radialFraction > 0.98) {
            return Optional.empty();
        }

        double altitudeDeg = 90.0 - fisheyeRadiusToZenithFraction(radialFraction, projection.radialPower()) * 90.0;
        double imageAngleDeg = normalizeDegrees(Math.atan2(dx, dy) * RAD_TO_DEG);
        double azimuthDeg = normalizeDegrees(imageAngleDeg - projection.rotationDeg());
        return Optional.of(new HorizontalCoordinate(altitudeDeg, azimuthDeg));
    }

    private HorizontalCoordinate skyToHorizontal(double raDeg, double decDeg, double siderealTimeDeg) {
        double hourAngleRad = normalizeSignedDegrees(siderealTimeDeg - raDeg) * DEG_TO_RAD;
        double decRad = decDeg * DEG_TO_RAD;
        double latRad = siteLatitudeDeg * DEG_TO_RAD;
        double sinAlt = Math.sin(decRad) * Math.sin(latRad)
                + Math.cos(decRad) * Math.cos(latRad) * Math.cos(hourAngleRad);
        double altitudeRad = Math.asin(Math.max(-1, Math.min(1, sinAlt)));
        double cosAlt = Math.max(1.0e-9, Math.cos(altitudeRad));
        double sinAz = -Math.cos(decRad) * Math.sin(hourAngleRad) / cosAlt;
        double cosAz = (Math.sin(decRad) - Math.sin(altitudeRad) * Math.sin(latRad))
                / (cosAlt * Math.cos(latRad));
        double azimuthDeg = normalizeDegrees(Math.atan2(sinAz, cosAz) * RAD_TO_DEG);
        return new HorizontalCoordinate(altitudeRad * RAD_TO_DEG, azimuthDeg);
    }

    private SkyCoordinate horizontalToSky(HorizontalCoordinate coordinate, double siderealTimeDeg) {
        double altitudeRad = coordinate.altitudeDeg() * DEG_TO_RAD;
        double azimuthRad = coordinate.azimuthDeg() * DEG_TO_RAD;
        double latRad = siteLatitudeDeg * DEG_TO_RAD;
        double sinDec = Math.sin(altitudeRad) * Math.sin(latRad)
                + Math.cos(altitudeRad) * Math.cos(latRad) * Math.cos(azimuthRad);
        double decRad = Math.asin(Math.max(-1, Math.min(1, sinDec)));
        double cosDec = Math.max(1.0e-9, Math.cos(decRad));
        double sinHourAngle = -Math.sin(azimuthRad) * Math.cos(altitudeRad) / cosDec;
        double cosHourAngle = (Math.sin(altitudeRad) - Math.sin(latRad) * Math.sin(decRad))
                / (Math.cos(latRad) * cosDec);
        double hourAngleDeg = Math.atan2(sinHourAngle, cosHourAngle) * RAD_TO_DEG;
        return new SkyCoordinate(normalizeDegrees(siderealTimeDeg - hourAngleDeg), decRad * RAD_TO_DEG);
    }

    private double allSkyMatchTolerancePx(AllSkyGeometry geometry) {
        return Math.max(18.0, geometry.radius() * 0.028);
    }

    private double[] fisheyeRadialPowers(PlateSolveCrop crop) {
        if (isQhy5iii678Frame(crop)) {
            return FISHEYE_RADIAL_POWERS;
        }

        return DEFAULT_RADIAL_POWERS;
    }

    private boolean isQhy5iii678Frame(PlateSolveCrop crop) {
        return Math.abs(crop.originalWidth() - QHY5III678_EFFECTIVE_WIDTH_PX) <= 16
                && Math.abs(crop.originalHeight() - QHY5III678_EFFECTIVE_HEIGHT_PX) <= 16
                && QHY5III678_PIXEL_SIZE_UM > 0;
    }

    private double zenithToFisheyeRadiusFraction(double zenithFraction, double radialPower) {
        return Math.pow(Math.max(0, Math.min(1, zenithFraction)), clampRadialPower(radialPower));
    }

    private double fisheyeRadiusToZenithFraction(double radialFraction, double radialPower) {
        return Math.pow(Math.max(0, Math.min(1, radialFraction)), 1.0 / clampRadialPower(radialPower));
    }

    private double clampRadialPower(double radialPower) {
        if (!Double.isFinite(radialPower)) {
            return 1.0;
        }
        return Math.max(0.55, Math.min(1.55, radialPower));
    }

    private double distancePx(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private int sampleRgb(BufferedImage image, double x, double y) {
        if (x < 0 || y < 0 || x >= image.getWidth() - 1 || y >= image.getHeight() - 1) {
            return 0;
        }

        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(image.getWidth() - 1, x0 + 1);
        int y1 = Math.min(image.getHeight() - 1, y0 + 1);
        double tx = x - x0;
        double ty = y - y0;
        return blendRgb(
                blendRgb(image.getRGB(x0, y0), image.getRGB(x1, y0), tx),
                blendRgb(image.getRGB(x0, y1), image.getRGB(x1, y1), tx),
                ty
        );
    }

    private int blendRgb(int first, int second, double amount) {
        double clamped = Math.max(0, Math.min(1, amount));
        int red = (int) Math.round(((first >> 16) & 0xff) * (1.0 - clamped) + ((second >> 16) & 0xff) * clamped);
        int green = (int) Math.round(((first >> 8) & 0xff) * (1.0 - clamped) + ((second >> 8) & 0xff) * clamped);
        int blue = (int) Math.round((first & 0xff) * (1.0 - clamped) + (second & 0xff) * clamped);
        return (red << 16) | (green << 8) | blue;
    }

    private String appendSolverLog(String solverLog, String message) {
        if (solverLog == null || solverLog.isBlank()) {
            return message;
        }
        return compactSolverLog(solverLog + "\n" + message);
    }

    private String compactSolverLog(String solverLog) {
        if (solverLog == null || solverLog.length() <= 20_000) {
            return solverLog;
        }

        return "[solver log trimmed to last 20000 characters]\n"
                + solverLog.substring(solverLog.length() - 20_000);
    }

    private PlateSolveResult complete(
            long imgId,
            PlateSolveStatus status,
            String message,
            PlateSolveCrop crop,
            PlateSolveSolution solution,
            List<PlateSolveStar> stars) {
        return new PlateSolveResult(
                imgId,
                status,
                message,
                false,
                Instant.now(),
                crop,
                solution,
                progressCache.get(imgId),
                stars
        );
    }

    private PlateSolveResult statusOnly(long imgId, PlateSolveStatus status, String message) {
        return new PlateSolveResult(
                imgId,
                status,
                message,
                false,
                Instant.now(),
                null,
                null,
                progressCache.get(imgId),
                List.of()
        );
    }

    private void updateProgress(long imgId, String phase, int percent, String detail, List<String> logTail) {
        PlateSolveProgress previous = progressCache.get(imgId);
        Instant startedAt = previous == null ? Instant.now() : previous.startedAt();
        progressCache.put(imgId, newProgress(phase, percent, detail, logTail, startedAt));
    }

    private PlateSolveProgress newProgress(
            String phase,
            int percent,
            String detail,
            List<String> logTail,
            Instant startedAt) {
        return new PlateSolveProgress(
                phase,
                Math.max(0, Math.min(100, percent)),
                detail == null || detail.isBlank() ? "Plate solve is running." : detail,
                logTail == null ? List.of() : List.copyOf(logTail),
                startedAt,
                Instant.now(),
                resolvedSolverCommand
        );
    }

    private List<String> tailLog(String log, int maxLines) {
        if (log == null || log.isBlank() || maxLines <= 0) {
            return List.of();
        }

        String[] lines = log.replace("\r", "").split("\n");
        int start = Math.max(0, lines.length - maxLines);
        List<String> tail = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                tail.add(lines[i]);
            }
        }
        return tail;
    }

    private Optional<PlateSolveResult> applyCachedCalibration(
            long imgId,
            Image image,
            PlateSolveCrop crop,
            List<PlateSolveStar> stars) {
        if (!calibrationCacheEnabled) {
            return Optional.empty();
        }

        CameraCalibration calibration = calibrationCache.get(cameraKey(image));
        if (calibration == null || calibration.expiredFor(image.getTimestamp(), calibrationCacheMaxAge)) {
            return Optional.empty();
        }

        List<PlateSolveStar> identifiedStars = identifyStarsWithWcs(stars, calibration.wcsHeader(), calibration.crop());
        PlateSolveResult result = complete(
                imgId,
                PlateSolveStatus.SOLVED,
                "Used cached camera calibration; skipped full solve-field run.",
                crop,
                calibration.solution(),
                identifiedStars
        );
        return Optional.of(result.withCached(true));
    }

    private void cacheCalibration(Image image, PlateSolveCrop crop, WcsHeader wcsHeader, PlateSolveSolution solution) {
        if (!calibrationCacheEnabled) {
            return;
        }

        calibrationCache.put(cameraKey(image), new CameraCalibration(image.getTimestamp(), crop, wcsHeader, solution));
    }

    private String cameraKey(Image image) {
        if (image.getCameraId() != null && !image.getCameraId().isBlank()) {
            return image.getCameraId();
        }

        if (image.getSiteName() != null && !image.getSiteName().isBlank()) {
            return image.getSiteName();
        }

        return "default";
    }

    private List<PlateSolveStar> identifyStarsWithWcs(List<PlateSolveStar> stars, WcsHeader wcsHeader, PlateSolveCrop wcsCrop) {
        return stars.stream()
                .map(star -> {
                    SkyCoordinate coordinate = pixelToSky(star.x() - wcsCrop.x(), star.y() - wcsCrop.y(), wcsHeader);
                    Optional<CatalogMatch> match = matchCatalog(coordinate);
                    return new PlateSolveStar(
                            star.id(),
                            star.x(),
                            star.y(),
                            star.cropX(),
                            star.cropY(),
                            star.brightness(),
                            coordinate.raDeg(),
                            coordinate.decDeg(),
                            match.map(CatalogMatch::name).orElse(null),
                            match.map(CatalogMatch::magnitude).orElse(null),
                            match.map(CatalogMatch::distanceArcsec).orElse(null),
                            match.map(CatalogMatch::identifiers).orElse(List.of()),
                            match.map(CatalogMatch::links).orElseGet(() -> coordinateLinks(coordinate)),
                            true
                    );
                })
                .toList();
    }

    private Optional<CatalogMatch> matchCatalog(SkyCoordinate coordinate) {
        return matchCatalog(coordinate, catalogMatchRadiusDeg);
    }

    private Optional<CatalogMatch> matchCatalog(SkyCoordinate coordinate, double radiusDeg) {
        List<CatalogStar> catalog = getCatalogStars();
        CatalogMatch best = null;

        for (CatalogStar star : catalog) {
            double distanceDeg = angularDistanceDeg(coordinate.raDeg(), coordinate.decDeg(), star.raDeg(), star.decDeg());
            if (distanceDeg > radiusDeg) {
                continue;
            }

            if (best == null || distanceDeg < best.distanceArcsec() / 3600.0) {
                best = new CatalogMatch(
                        star.name(),
                        star.magnitude(),
                        distanceDeg * 3600.0,
                        starIdentifiers(star),
                        starLinks(star)
                );
            }
        }

        return Optional.ofNullable(best);
    }

    private List<CatalogStar> getCatalogStars() {
        List<CatalogStar> cached = catalogStars;
        if (cached != null) {
            return cached;
        }

        if (catalogPath.isBlank()) {
            catalogStars = List.of();
            return catalogStars;
        }

        List<CatalogStar> loaded = new ArrayList<>();
        try {
            List<String> lines = readCatalogLines();
            List<String> header = null;

            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",", -1);
                if (header == null && isCatalogHeader(parts)) {
                    header = Arrays.stream(parts)
                            .map(this::normalizeHeader)
                            .toList();
                    continue;
                }

                Optional<CatalogStar> star = header == null
                        ? parseLegacyCatalogStar(parts)
                        : parseHeaderCatalogStar(header, parts);
                star.ifPresent(loaded::add);
            }
        } catch (IOException ignored) {
            loaded = List.of();
        }

        List<CatalogStar> merged = new ArrayList<>(loaded);
        merged.addAll(getOnlineCatalogStars());
        catalogStars = merged;
        return catalogStars;
    }

    private List<CatalogStar> getOnlineCatalogStars() {
        if (!onlineCatalogEnabled) {
            return List.of();
        }

        List<CatalogStar> cached = onlineCatalogStars;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (onlineCatalogStars != null) {
                return onlineCatalogStars;
            }

            List<CatalogStar> loaded = loadOnlineCatalogFromCache(false);
            if (loaded.isEmpty() || onlineCatalogCacheExpired()) {
                List<CatalogStar> refreshed = fetchOnlineCatalog();
                if (!refreshed.isEmpty()) {
                    loaded = refreshed;
                    writeOnlineCatalogCache(refreshed);
                } else if (loaded.isEmpty()) {
                    loaded = loadOnlineCatalogFromCache(true);
                }
            }

            onlineCatalogStars = loaded;
            return onlineCatalogStars;
        }
    }

    private boolean onlineCatalogCacheExpired() {
        try {
            if (!Files.exists(onlineCatalogCacheFile)) {
                return true;
            }

            Instant modifiedAt = Files.getLastModifiedTime(onlineCatalogCacheFile).toInstant();
            return modifiedAt.plus(onlineCatalogCacheTtl).isBefore(Instant.now());
        } catch (IOException e) {
            return true;
        }
    }

    private List<CatalogStar> loadOnlineCatalogFromCache(boolean allowExpired) {
        try {
            if (!Files.exists(onlineCatalogCacheFile) || (!allowExpired && onlineCatalogCacheExpired())) {
                return List.of();
            }

            return parseOnlineCatalogCsv(Files.readString(onlineCatalogCacheFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<CatalogStar> fetchOnlineCatalog() {
        try {
            String query = "SELECT TOP " + onlineCatalogMaxRows
                    + " basic.oid,basic.main_id,basic.ra,basic.dec,basic.otype,allfluxes.V "
                    + "FROM basic LEFT OUTER JOIN allfluxes ON basic.oid=allfluxes.oidref "
                    + "WHERE basic.ra IS NOT NULL AND basic.dec IS NOT NULL "
                    + "AND allfluxes.V IS NOT NULL AND allfluxes.V <= "
                    + String.format(Locale.ROOT, "%.2f", onlineCatalogMagnitudeLimit)
                    + " ORDER BY V ASC";
            String body = "REQUEST=doQuery"
                    + "&LANG=ADQL"
                    + "&FORMAT=csv"
                    + "&QUERY=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(onlineCatalogUrl)
                    .timeout(onlineCatalogTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().startsWith("<?xml")) {
                return List.of();
            }

            return parseOnlineCatalogCsv(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private void writeOnlineCatalogCache(List<CatalogStar> stars) {
        try {
            Path parent = onlineCatalogCacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("name,ra,dec,mag,simbad_oid,otype");
            for (CatalogStar star : stars) {
                lines.add(csvValue(star.name())
                        + "," + star.raDeg()
                        + "," + star.decDeg()
                        + "," + Objects.toString(star.magnitude(), "")
                        + "," + csvValue(star.identifiers().getOrDefault("SIMBAD OID", ""))
                        + "," + csvValue(star.objectType()));
            }
            Files.write(onlineCatalogCacheFile, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private List<CatalogStar> parseOnlineCatalogCsv(String csv) {
        List<CatalogStar> stars = new ArrayList<>();
        List<String> header = null;

        for (String line : csv.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            List<String> parts = parseCsvLine(line);
            if (header == null) {
                header = parts.stream().map(this::normalizeHeader).toList();
                continue;
            }

            parseOnlineCatalogStar(header, parts).ifPresent(stars::add);
        }

        return stars;
    }

    private Optional<CatalogStar> parseOnlineCatalogStar(List<String> header, List<String> parts) {
        Optional<Double> ra = valueFor(header, parts, "ra").flatMap(this::parseDouble);
        Optional<Double> dec = valueFor(header, parts, "dec").flatMap(this::parseDouble);
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        String objectType = valueFor(header, parts, "otype", "objecttype").orElse("");
        if (!isStellarObjectType(objectType)) {
            return Optional.empty();
        }

        String name = valueFor(header, parts, "mainid", "main_id", "name")
                .orElse("SIMBAD star")
                .replaceAll("\\s+", " ")
                .trim();
        Double magnitude = valueFor(header, parts, "v", "mag", "magnitude")
                .flatMap(this::parseDouble)
                .orElse(null);
        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifier(identifiers, "SIMBAD", name);
        addIdentifier(identifiers, "SIMBAD OID", valueFor(header, parts, "oid", "simbadoid").orElse(""));
        addIdentifiersFromText(identifiers, name);
        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, "", objectType));
    }

    private boolean isStellarObjectType(String objectType) {
        return objectType != null && objectType.contains("*");
    }

    private List<String> readCatalogLines() throws IOException {
        if (catalogPath.startsWith("classpath:")) {
            String resourcePath = catalogPath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                return List.of();
            }

            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .toList();
            }
        }

        Path path = Path.of(catalogPath);
        if (!Files.exists(path)) {
            return List.of();
        }

        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private Optional<CatalogStar> parseLegacyCatalogStar(String[] parts) {
        if (parts.length < 3) {
            return Optional.empty();
        }

        Optional<Double> ra = parseDouble(parts[1].trim());
        Optional<Double> dec = parseDouble(parts[2].trim());
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        String name = parts[0].trim();
        Double magnitude = parts.length >= 4 ? parseDouble(parts[3].trim()).orElse(null) : null;
        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifiersFromText(identifiers, name);
        addIdentifier(identifiers, "HIP", part(parts, 4));
        addIdentifier(identifiers, "HD", part(parts, 5));
        addIdentifier(identifiers, "HR", part(parts, 6));
        addIdentifier(identifiers, "SAO", part(parts, 7));
        addIdentifier(identifiers, "Gaia DR3", part(parts, 8));
        addIdentifier(identifiers, "TYC", part(parts, 9));
        String wikipediaTitle = part(parts, 10);

        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, wikipediaTitle, ""));
    }

    private Optional<CatalogStar> parseHeaderCatalogStar(List<String> header, String[] parts) {
        Optional<Double> ra = valueFor(header, parts, "ra", "radeg", "rightascension", "rightascensiondeg")
                .flatMap(this::parseDouble);
        Optional<Double> dec = valueFor(header, parts, "dec", "decdeg", "declination", "declinationdeg")
                .flatMap(this::parseDouble);
        if (ra.isEmpty() || dec.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> identifiers = new LinkedHashMap<>();
        addIdentifier(identifiers, "HIP", valueFor(header, parts, "hip", "hipid", "hipparcos").orElse(""));
        addIdentifier(identifiers, "HD", valueFor(header, parts, "hd", "hdid", "henrydraper").orElse(""));
        addIdentifier(identifiers, "HR", valueFor(header, parts, "hr", "bs", "brightstar", "harvardrevised").orElse(""));
        addIdentifier(identifiers, "SAO", valueFor(header, parts, "sao").orElse(""));
        addIdentifier(identifiers, "Gaia DR3", valueFor(header, parts, "gaia", "gaiadr3", "dr3", "sourceid", "gaiasourceid").orElse(""));
        addIdentifier(identifiers, "TYC", valueFor(header, parts, "tyc", "tycho", "tycho2").orElse(""));

        String name = valueFor(header, parts, "name", "proper", "propername", "designation", "mainid")
                .orElseGet(() -> identifiers.isEmpty()
                        ? "Unknown star"
                        : identifierLabel(identifiers.entrySet().iterator().next().getKey(), identifiers.entrySet().iterator().next().getValue()));
        addIdentifiersFromText(identifiers, name);
        Double magnitude = valueFor(header, parts, "mag", "magnitude", "vmag", "visualmag", "photgmeanmag")
                .flatMap(this::parseDouble)
                .orElse(null);
        String wikipediaTitle = valueFor(header, parts, "wikipedia", "wikipediatitle", "wiki", "wikititle").orElse("");

        return Optional.of(new CatalogStar(name, ra.get(), dec.get(), magnitude, identifiers, wikipediaTitle, ""));
    }

    private boolean isCatalogHeader(String[] parts) {
        List<String> normalized = Arrays.stream(parts)
                .map(this::normalizeHeader)
                .toList();
        return normalized.stream().anyMatch(value -> value.equals("ra") || value.equals("radeg") || value.equals("rightascension"))
                && normalized.stream().anyMatch(value -> value.equals("dec") || value.equals("decdeg") || value.equals("declination"));
    }

    private Optional<String> valueFor(List<String> header, String[] parts, String... aliases) {
        for (String alias : aliases) {
            String normalizedAlias = normalizeHeader(alias);
            int index = header.indexOf(normalizedAlias);
            if (index >= 0 && index < parts.length && !parts[index].isBlank()) {
                return Optional.of(parts[index].trim());
            }
        }

        return Optional.empty();
    }

    private Optional<String> valueFor(List<String> header, List<String> parts, String... aliases) {
        for (String alias : aliases) {
            String normalizedAlias = normalizeHeader(alias);
            int index = header.indexOf(normalizedAlias);
            if (index >= 0 && index < parts.size() && !parts.get(index).isBlank()) {
                return Optional.of(parts.get(index).trim());
            }
        }

        return Optional.empty();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        values.add(current.toString());
        return values;
    }

    private String csvValue(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String part(String[] parts, int index) {
        return index >= 0 && index < parts.length ? parts[index].trim() : "";
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void addIdentifiersFromText(Map<String, String> identifiers, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        Matcher matcher = IDENTIFIER_PATTERN.matcher(value);
        while (matcher.find()) {
            addIdentifier(identifiers, matcher.group(1).toUpperCase(), matcher.group(2));
        }

        Matcher gaiaMatcher = GAIA_IDENTIFIER_PATTERN.matcher(value);
        while (gaiaMatcher.find()) {
            addIdentifier(identifiers, "Gaia DR3", gaiaMatcher.group(1));
        }
    }

    private void addIdentifier(Map<String, String> identifiers, String catalog, String value) {
        String cleaned = cleanIdentifierValue(catalog, value);
        if (cleaned.isBlank() || cleaned.equals("0")) {
            return;
        }

        identifiers.putIfAbsent(catalog, cleaned);
    }

    private String cleanIdentifierValue(String catalog, String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.trim();
        if (cleaned.isBlank()) {
            return "";
        }

        if ("SIMBAD".equals(catalog)) {
            return cleaned;
        }

        for (String prefix : List.of(catalog, catalog.replace(" DR3", ""), "Gaia", "HIP", "HD", "HR", "SAO", "TYC")) {
            cleaned = cleaned.replaceFirst("(?i)^" + Pattern.quote(prefix) + "\\s*", "");
        }

        return cleaned.trim();
    }

    private List<PlateSolveStarIdentifier> starIdentifiers(CatalogStar star) {
        return star.identifiers().entrySet().stream()
                .map(entry -> {
                    String label = identifierLabel(entry.getKey(), entry.getValue());
                    return new PlateSolveStarIdentifier(
                            entry.getKey(),
                            entry.getValue(),
                            label,
                            simbadIdentifierUrl(label)
                    );
                })
                .toList();
    }

    private List<PlateSolveStarLink> starLinks(CatalogStar star) {
        List<PlateSolveStarLink> links = new ArrayList<>();
        String primaryIdentifier = star.identifiers().isEmpty()
                ? star.name()
                : identifierLabel(star.identifiers().entrySet().iterator().next().getKey(), star.identifiers().entrySet().iterator().next().getValue());

        if (star.wikipediaTitle() != null && !star.wikipediaTitle().isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaPageUrl(star.wikipediaTitle())));
        } else if (star.name() != null && !star.name().isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaSearchUrl(star.name())));
        } else if (!primaryIdentifier.isBlank()) {
            links.add(new PlateSolveStarLink("Wikipedia", wikipediaSearchUrl(primaryIdentifier)));
        }

        if (primaryIdentifier != null && !primaryIdentifier.isBlank()) {
            links.add(new PlateSolveStarLink("SIMBAD", simbadIdentifierUrl(primaryIdentifier)));
        }

        star.identifiers().entrySet().stream()
                .filter(entry -> entry.getKey().equals("HIP"))
                .findFirst()
                .ifPresent(entry -> links.add(new PlateSolveStarLink(
                        "VizieR HIP",
                        "https://vizier.cds.unistra.fr/viz-bin/VizieR-3?-source=I/239/hip_main&HIP=" + urlEncode(entry.getValue())
                )));

        return links;
    }

    private List<PlateSolveStarLink> coordinateLinks(SkyCoordinate coordinate) {
        String coordinates = String.format("%.6f %.6f", coordinate.raDeg(), coordinate.decDeg());
        return List.of(new PlateSolveStarLink(
                "SIMBAD coordinate search",
                "https://simbad.u-strasbg.fr/simbad/sim-coo?Coord=" + urlEncode(coordinates) + "&Radius=5&Radius.unit=arcsec"
        ));
    }

    private String identifierLabel(String catalog, String value) {
        if ("SIMBAD".equals(catalog)) {
            return value;
        }
        return catalog + " " + value;
    }

    private String simbadIdentifierUrl(String identifier) {
        return "https://simbad.u-strasbg.fr/simbad/sim-id?Ident=" + urlEncode(identifier) + "&NbIdent=1";
    }

    private String wikipediaPageUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + urlEncode(title.trim().replace(' ', '_')).replace("+", "%20");
    }

    private String wikipediaSearchUrl(String query) {
        return "https://en.wikipedia.org/wiki/Special:Search?search=" + urlEncode(query);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String resolveSolverCommand(String command) {
        if (command == null || command.isBlank()) {
            return "solve-field";
        }

        Path configuredPath = Path.of(command);
        if (configuredPath.isAbsolute() || command.contains("/")) {
            return command;
        }

        for (String pathEntry : commandSearchPaths()) {
            Path candidate = Path.of(pathEntry, command);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }

        return command;
    }

    private List<String> commandSearchPaths() {
        List<String> paths = new ArrayList<>();
        String environmentPath = System.getenv("PATH");
        if (environmentPath != null && !environmentPath.isBlank()) {
            paths.addAll(Arrays.asList(environmentPath.split(System.getProperty("path.separator"))));
        }
        paths.add("/opt/homebrew/bin");
        paths.add("/usr/local/bin");
        paths.add("/usr/bin");
        paths.add("/bin");
        return paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    private boolean isCommandAvailable(String command) {
        Path commandPath = Path.of(command);
        if (commandPath.isAbsolute()) {
            return Files.isExecutable(commandPath);
        }

        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }

        for (String entry : path.split(System.getProperty("path.separator"))) {
            if (Files.isExecutable(Path.of(entry, command))) {
                return true;
            }
        }

        return false;
    }

    private Optional<SkyCoordinate> zenithCoordinate(Image image) {
        return localSiderealTimeDeg(image)
                .map(siderealTimeDeg -> new SkyCoordinate(siderealTimeDeg, siteLatitudeDeg));
    }

    private Optional<Double> localSiderealTimeDeg(Image image) {
        if (image.getTimestamp() == null) {
            return Optional.empty();
        }

        double julianDate = image.getTimestamp().toEpochMilli() / 86_400_000.0 + 2_440_587.5;
        double daysSinceJ2000 = julianDate - 2_451_545.0;
        double centuriesSinceJ2000 = daysSinceJ2000 / 36_525.0;
        double gmstDeg = 280.46061837
                + 360.98564736629 * daysSinceJ2000
                + 0.000387933 * centuriesSinceJ2000 * centuriesSinceJ2000
                - centuriesSinceJ2000 * centuriesSinceJ2000 * centuriesSinceJ2000 / 38_710_000.0;
        return Optional.of(normalizeDegrees(gmstDeg + siteLongitudeDeg));
    }

    private int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private int percentile(int[] histogram, int total, double percentile) {
        int target = (int) Math.ceil(total * percentile);
        int running = 0;

        for (int i = 0; i < histogram.length; i++) {
            running += histogram[i];
            if (running >= target) {
                return i;
            }
        }

        return histogram.length - 1;
    }

    private double clampPercentile(double value, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.5, Math.min(0.9999, value));
    }

    private int localMean(long[] integral, int width, int height, int x, int y, int radius) {
        int x1 = Math.max(0, x - radius);
        int y1 = Math.max(0, y - radius);
        int x2 = Math.min(width - 1, x + radius);
        int y2 = Math.min(height - 1, y + radius);
        int stride = width + 1;
        long sum = integral[(y2 + 1) * stride + x2 + 1]
                - integral[y1 * stride + x2 + 1]
                - integral[(y2 + 1) * stride + x1]
                + integral[y1 * stride + x1];
        int area = (x2 - x1 + 1) * (y2 - y1 + 1);
        return (int) (sum / Math.max(1, area));
    }

    private StarCandidate componentCandidate(
            int[] gray,
            int[] contrast,
            int[] background,
            boolean[] visited,
            int width,
            int height,
            int startX,
            int startY,
            int threshold) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startY * width + startX);
        visited[startY * width + startX] = true;

        int area = 0;
        int peak = 0;
        int minX = startX;
        int maxX = startX;
        int minY = startY;
        int maxY = startY;
        double weightedX = 0;
        double weightedY = 0;
        double totalWeight = 0;
        int backgroundSum = 0;

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            int value = contrast[index];
            int weight = Math.max(1, value);
            area++;
            peak = Math.max(peak, gray[index]);
            backgroundSum += background[index];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            weightedX += x * weight;
            weightedY += y * weight;
            totalWeight += weight;

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }

                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue;
                    }

                    int neighbor = ny * width + nx;
                    if (!visited[neighbor] && contrast[neighbor] >= threshold) {
                        visited[neighbor] = true;
                        queue.add(neighbor);
                    }
                }
            }
        }

        int componentWidth = maxX - minX + 1;
        int componentHeight = maxY - minY + 1;
        double elongation = Math.max(componentWidth, componentHeight) / (double) Math.max(1, Math.min(componentWidth, componentHeight));
        if (area > maxStarArea
                || componentWidth > maxStarDiameter
                || componentHeight > maxStarDiameter
                || elongation > 3.0
                || totalWeight == 0) {
            return null;
        }

        return new StarCandidate(weightedX / totalWeight, weightedY / totalWeight, peak, backgroundSum / Math.max(1, area));
    }

    private Optional<Double> headerDouble(Map<String, String> header, String key) {
        return Optional.ofNullable(header.get(key))
                .flatMap(this::parseDouble);
    }

    private SkyCoordinate pixelToSky(double pixelX, double pixelY, WcsHeader wcsHeader) {
        double dx = pixelX + 1.0 - wcsHeader.crpix1();
        double dy = pixelY + 1.0 - wcsHeader.crpix2();
        double xi = (wcsHeader.cd11() * dx + wcsHeader.cd12() * dy) * DEG_TO_RAD;
        double eta = (wcsHeader.cd21() * dx + wcsHeader.cd22() * dy) * DEG_TO_RAD;
        double ra0 = wcsHeader.crval1() * DEG_TO_RAD;
        double dec0 = wcsHeader.crval2() * DEG_TO_RAD;
        double denominator = Math.cos(dec0) - eta * Math.sin(dec0);
        double ra = normalizeDegrees((Math.atan2(xi, denominator) + ra0) * RAD_TO_DEG);
        double dec = Math.atan2(
                Math.sin(dec0) + eta * Math.cos(dec0),
                Math.sqrt(xi * xi + denominator * denominator)
        ) * RAD_TO_DEG;
        return new SkyCoordinate(ra, dec);
    }

    private double angularDistanceDeg(SkyCoordinate first, SkyCoordinate second) {
        return angularDistanceDeg(first.raDeg(), first.decDeg(), second.raDeg(), second.decDeg());
    }

    private double angularDistanceDeg(double ra1Deg, double dec1Deg, double ra2Deg, double dec2Deg) {
        double ra1 = ra1Deg * DEG_TO_RAD;
        double dec1 = dec1Deg * DEG_TO_RAD;
        double ra2 = ra2Deg * DEG_TO_RAD;
        double dec2 = dec2Deg * DEG_TO_RAD;
        double cosine = Math.sin(dec1) * Math.sin(dec2)
                + Math.cos(dec1) * Math.cos(dec2) * Math.cos(ra1 - ra2);
        return Math.acos(Math.max(-1, Math.min(1, cosine))) * RAD_TO_DEG;
    }

    private double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }

    private double normalizeSignedDegrees(double value) {
        double normalized = normalizeDegrees(value);
        return normalized > 180.0 ? normalized - 360.0 : normalized;
    }

    private boolean isLocalMaximum(int[] gray, int width, int x, int y, int value) {
        int index = y * width + x;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }

                if (gray[index + dy * width + dx] > value) {
                    return false;
                }
            }
        }

        return true;
    }

    private StarCandidate centroid(int[] gray, int width, int height, int x, int y, int threshold) {
        double weightedX = 0;
        double weightedY = 0;
        double totalWeight = 0;
        int peak = gray[y * width + x];

        for (int dy = -2; dy <= 2; dy++) {
            int cy = y + dy;
            if (cy < 0 || cy >= height) {
                continue;
            }

            for (int dx = -2; dx <= 2; dx++) {
                int cx = x + dx;
                if (cx < 0 || cx >= width) {
                    continue;
                }

                int value = gray[cy * width + cx];
                double weight = Math.max(0, value - threshold / 2.0);
                weightedX += cx * weight;
                weightedY += cy * weight;
                totalWeight += weight;
            }
        }

        if (totalWeight == 0) {
            return new StarCandidate(x, y, peak, 0);
        }

        return new StarCandidate(weightedX / totalWeight, weightedY / totalWeight, peak, 0);
    }

    private BufferedImage toRgb(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private Optional<Double> parseDouble(String value) {
        try {
            return Optional.of(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<URI> parseUri(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(URI.create(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record CropImage(BufferedImage image, PlateSolveCrop crop) {
    }

    private record SourceFrame(
            BufferedImage image,
            Path sourcePath,
            String sourceKind,
            int cropThreshold,
            int starMinContrast,
            double starContrastPercentile) {
    }

    private record FitsImage(int width, int height, int channels, float[] pixels) {
    }

    private record FitsHdu(
            int index,
            Map<String, String> header,
            int dataOffset,
            int nextOffset,
            int bitpix,
            int[] axes,
            double bscale,
            double bzero,
            boolean plainImage,
            boolean compressedImage) {
    }

    private record StarCandidate(double x, double y, int brightness, int background) {
    }

    private record SolverRun(boolean solved, Path wcsPath, String log) {
    }

    private record WcsHeader(
            double crpix1,
            double crpix2,
            double crval1,
            double crval2,
            double cd11,
            double cd12,
            double cd21,
            double cd22,
            int width,
            int height) {
    }

    private record SkyCoordinate(double raDeg, double decDeg) {
    }

    private record HorizontalCoordinate(double altitudeDeg, double azimuthDeg) {
    }

    private record HorizontalCatalogStar(CatalogStar catalogStar, double altitudeDeg, double azimuthDeg) {
    }

    private record ProjectedPoint(double x, double y) {
    }

    private record XySource(double x, double y, double flux) {
    }

    private record WcsCorrespondence(double fieldX, double fieldY, double raDeg, double decDeg) {
    }

    private record SnappedCatalogStar(PlateSolveStar star, int sourceDetectionId) {
    }

    private record UndistortedSolveInput(Path path, int width, int height, double fieldWidthDeg) {
    }

    private record AllSkyGeometry(double centerX, double centerY, double radius) {
    }

    private record AllSkyProjection(
            double centerX,
            double centerY,
            double radius,
            double rotationDeg,
            double radialPower,
            int originalWidth,
            int originalHeight,
            int matchedStars,
            double rmsErrorPx) {
    }

    private record ProjectionScore(AllSkyProjection projection, double score) {
    }

    private record AllSkySolve(
            AllSkyProjection projection,
            PlateSolveSolution solution,
            List<PlateSolveStar> stars) {
    }

    private record CameraCalibration(
            Instant timestamp,
            PlateSolveCrop crop,
            WcsHeader wcsHeader,
            PlateSolveSolution solution) {
        private boolean expiredFor(Instant imageTimestamp, Duration maxAge) {
            if (timestamp == null || imageTimestamp == null) {
                return false;
            }

            long ageSeconds = Math.abs(Duration.between(timestamp, imageTimestamp).toSeconds());
            return ageSeconds > maxAge.toSeconds();
        }
    }

    private record CatalogStar(
            String name,
            double raDeg,
            double decDeg,
            Double magnitude,
            Map<String, String> identifiers,
            String wikipediaTitle,
            String objectType) {
    }

    private record CatalogMatch(
            String name,
            Double magnitude,
            double distanceArcsec,
            List<PlateSolveStarIdentifier> identifiers,
            List<PlateSolveStarLink> links) {
    }
}
