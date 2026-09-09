package edu.camserver.app.model.archive;

import java.time.Instant;

/**
 * @param format {@code plain}, {@code gzip} or {@code rice}; null when the file does not exist
 */
public record ArchiveFileInfo(
        String fileName,
        boolean exists,
        boolean gzipped,
        String format,
        String storedAs,
        long sizeBytes,
        Instant modifiedAt) {
}
