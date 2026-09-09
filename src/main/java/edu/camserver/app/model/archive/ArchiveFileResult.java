package edu.camserver.app.model.archive;

public record ArchiveFileResult(
        String fileName,
        String action,
        boolean changed,
        long bytesBefore,
        long bytesAfter,
        String message) {

    public static ArchiveFileResult skipped(String fileName, String action, long bytes, String message) {
        return new ArchiveFileResult(fileName, action, false, bytes, bytes, message);
    }
}
