package edu.camserver.app.controller;

import edu.camserver.app.model.archive.ArchiveFileInfo;
import edu.camserver.app.model.archive.ArchiveFileResult;
import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import edu.camserver.app.model.archive.ArchiveStats;
import edu.camserver.app.service.ArchiveAutoCompressor;
import edu.camserver.app.service.ImageArchiveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Admin API for the on-demand image archive (gzip or FITS tile compression, see
 * {@link ImageArchiveService}).
 *
 * <p>Read endpoints are open. Anything that changes files requires the token configured in
 * {@code app.images.archive.admin-token}, sent as {@code X-Archive-Token: <token>} or
 * {@code Authorization: Bearer <token>}. With no token configured the write endpoints are disabled.
 */
@RestController
@RequestMapping("/api/archive")
public class ImageArchiveController {
    private final ImageArchiveService archiveService;
    private final ArchiveAutoCompressor autoCompressor;
    private final String adminToken;

    public ImageArchiveController(ImageArchiveService archiveService,
                                  ArchiveAutoCompressor autoCompressor,
                                  @Value("${app.images.archive.admin-token:}") String adminToken) {
        this.archiveService = archiveService;
        this.autoCompressor = autoCompressor;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping("/stats")
    public ArchiveStats stats(@RequestParam(defaultValue = "false") boolean refresh) throws IOException {
        return archiveService.stats(refresh);
    }

    /** Archive format, tool availability and tuning, as the running instance sees them. */
    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam(defaultValue = "false") boolean reprobe) {
        if (reprobe) {
            archiveService.reprobeRice();
        }
        return archiveService.config();
    }

    /** Configuration and last outcome of the automatic old-frame compression. */
    @GetMapping("/auto")
    public Map<String, Object> auto() {
        return autoCompressor.status();
    }

    /** Runs the automatic compression now instead of waiting for the schedule. */
    @PostMapping("/auto/run")
    public ResponseEntity<?> runAuto(HttpServletRequest request) {
        requireAdmin(request);
        return autoCompressor.run("manual")
                .<ResponseEntity<?>>map(job -> ResponseEntity.accepted().body(job))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", autoCompressor.lastOutcome())));
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("active", archiveService.activeJob().orElse(null));
        body.put("jobs", archiveService.jobs());
        return body;
    }

    @GetMapping("/jobs/{id}")
    public ArchiveJob job(@PathVariable String id) {
        return archiveService.job(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job " + id));
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        if (!archiveService.cancel(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not running: " + id);
        }
        return ResponseEntity.accepted().body(Map.of("cancelled", id));
    }

    @PostMapping("/compress")
    public ResponseEntity<ArchiveJob> compress(@RequestBody ArchiveSelection selection, HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.accepted().body(startJob(ArchiveJob.Type.COMPRESS, selection));
    }

    @PostMapping("/decompress")
    public ResponseEntity<ArchiveJob> decompress(@RequestBody ArchiveSelection selection, HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.accepted().body(startJob(ArchiveJob.Type.DECOMPRESS, selection));
    }

    @GetMapping("/files/{fileName:.+}")
    public ArchiveFileInfo describe(@PathVariable String fileName) {
        try {
            return archiveService.describe(fileName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/files/{fileName:.+}/compress")
    public ArchiveFileResult compressFile(@PathVariable String fileName,
                                          @RequestParam(defaultValue = "false") boolean dryRun,
                                          HttpServletRequest request) {
        requireAdmin(request);
        return singleFile(() -> archiveService.compress(fileName, dryRun));
    }

    @PostMapping("/files/{fileName:.+}/decompress")
    public ArchiveFileResult decompressFile(@PathVariable String fileName,
                                            @RequestParam(defaultValue = "false") boolean dryRun,
                                            HttpServletRequest request) {
        requireAdmin(request);
        return singleFile(() -> archiveService.decompress(fileName, dryRun));
    }

    private ArchiveJob startJob(ArchiveJob.Type type, ArchiveSelection selection) {
        if (selection == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        try {
            return archiveService.startJob(type, selection);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private interface FileAction {
        ArchiveFileResult run() throws IOException;
    }

    private ArchiveFileResult singleFile(FileAction action) {
        try {
            return action.run();
        } catch (FileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such image file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void requireAdmin(HttpServletRequest request) {
        if (adminToken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Archive write operations are disabled: app.images.archive.admin-token is not configured");
        }
        String presented = request.getHeader("X-Archive-Token");
        if (presented == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                presented = auth.substring(7).trim();
            }
        }
        if (presented == null || !constantTimeEquals(presented, adminToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or missing archive token");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
