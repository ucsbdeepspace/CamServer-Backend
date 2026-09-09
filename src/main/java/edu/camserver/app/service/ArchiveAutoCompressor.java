package edu.camserver.app.service;

import edu.camserver.app.model.archive.ArchiveJob;
import edu.camserver.app.model.archive.ArchiveSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compresses FITS frames older than a configured age without anyone calling the archive API.
 *
 * <p>A run starts shortly after the application boots, so a backlog is worked through as soon as
 * the server is up, and then once a day on a cron schedule. Each run is an ordinary archive job:
 * it appears in {@code /api/archive/jobs}, can be cancelled there, and is skipped when another job
 * is already running. Frames are selected by the capture time in their file name; empty files and
 * anything modified in the last few minutes are left alone by the job itself.
 */
@Service
public class ArchiveAutoCompressor {
    private static final Logger log = LoggerFactory.getLogger(ArchiveAutoCompressor.class);

    private final ImageArchiveService archiveService;
    private final TaskScheduler taskScheduler;
    private final boolean enabled;
    private final int olderThanDays;
    private final String cron;
    private final Duration startupDelay;
    private final int maxFilesPerRun;
    private final AtomicReference<ArchiveJob> lastJob = new AtomicReference<>();
    private volatile Instant lastAttemptAt;
    private volatile String lastOutcome = "not run yet";

    public ArchiveAutoCompressor(
            ImageArchiveService archiveService,
            TaskScheduler taskScheduler,
            @Value("${app.images.archive.auto.enabled:true}") boolean enabled,
            @Value("${app.images.archive.auto.older-than-days:30}") int olderThanDays,
            @Value("${app.images.archive.auto.cron:0 30 4 * * *}") String cron,
            @Value("${app.images.archive.auto.startup-delay:PT3M}") Duration startupDelay,
            @Value("${app.images.archive.auto.max-files-per-run:0}") int maxFilesPerRun) {
        this.archiveService = archiveService;
        this.taskScheduler = taskScheduler;
        this.enabled = enabled;
        this.olderThanDays = Math.max(0, olderThanDays);
        this.cron = cron;
        this.startupDelay = startupDelay;
        this.maxFilesPerRun = Math.max(0, maxFilesPerRun);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStartupRun() {
        if (!enabled) {
            log.info("Auto-compress is disabled (app.images.archive.auto.enabled=false)");
            return;
        }
        Instant at = Instant.now().plus(startupDelay);
        log.info("Auto-compress: first run at {} (then cron '{}'), FITS frames older than {} day(s)",
                at, cron, olderThanDays);
        taskScheduler.schedule(() -> run("startup"), at);
    }

    @Scheduled(cron = "${app.images.archive.auto.cron:0 30 4 * * *}")
    public void scheduledRun() {
        run("schedule");
    }

    /**
     * Starts a compression run now. Returns the job, or empty when the run was skipped because
     * the feature is disabled or another archive job is already active.
     */
    public synchronized Optional<ArchiveJob> run(String trigger) {
        lastAttemptAt = Instant.now();
        boolean manual = "manual".equals(trigger);
        if (!enabled && !manual) {
            lastOutcome = "disabled";
            return Optional.empty();
        }

        Optional<String> problem = archiveService.compressionProblem();
        if (problem.isPresent()) {
            lastOutcome = "skipped: " + problem.get();
            log.warn("Auto-compress ({}) skipped: {}", trigger, problem.get());
            return Optional.empty();
        }

        ArchiveSelection selection = new ArchiveSelection();
        selection.setOlderThanDays(olderThanDays);
        if (maxFilesPerRun > 0) {
            selection.setLimit(maxFilesPerRun);
        }
        try {
            ArchiveJob job = archiveService.startJob(ArchiveJob.Type.COMPRESS, selection);
            lastJob.set(job);
            lastOutcome = "started job " + job.getId();
            log.info("Auto-compress ({}): started job {} for FITS frames older than {} day(s){}",
                    trigger, job.getId(), olderThanDays,
                    maxFilesPerRun > 0 ? ", at most " + maxFilesPerRun + " files" : "");
            return Optional.of(job);
        } catch (IllegalStateException e) {
            lastOutcome = "skipped: " + e.getMessage();
            log.info("Auto-compress ({}) skipped: {}", trigger, e.getMessage());
            return Optional.empty();
        }
    }

    public String lastOutcome() {
        return lastOutcome;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("format", archiveService.format().name().toLowerCase(java.util.Locale.ROOT));
        status.put("olderThanDays", olderThanDays);
        status.put("cron", cron);
        status.put("startupDelay", startupDelay.toString());
        status.put("maxFilesPerRun", maxFilesPerRun == 0 ? null : maxFilesPerRun);
        status.put("nextScheduledRun", enabled ? nextCronRun() : null);
        status.put("lastAttemptAt", lastAttemptAt);
        status.put("lastOutcome", lastOutcome);
        status.put("lastJob", lastJob.get());
        return status;
    }

    private LocalDateTime nextCronRun() {
        try {
            return CronExpression.parse(cron).next(LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
