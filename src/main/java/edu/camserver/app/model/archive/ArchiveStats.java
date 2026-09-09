package edu.camserver.app.model.archive;

import java.time.Instant;

public record ArchiveStats(
        Instant generatedAt,
        long scanMillis,
        String baseDir,
        long totalFiles,
        long totalBytes,
        long fitsPlainFiles,
        long fitsPlainBytes,
        long fitsGzipFiles,
        long fitsGzipBytes,
        long fitsRiceFiles,
        long fitsRiceBytes,
        long jpgFiles,
        long jpgBytes,
        long otherFiles,
        long otherBytes,
        long emptyFiles,
        long diskTotalBytes,
        long diskFreeBytes) {
}
