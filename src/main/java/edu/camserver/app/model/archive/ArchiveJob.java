package edu.camserver.app.model.archive;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Progress record of one compress/decompress run. Mutated by worker threads, read by the API.
 */
public class ArchiveJob {
    public enum Type { COMPRESS, DECOMPRESS }

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    private static final int MAX_ERRORS = 25;

    private final String id = UUID.randomUUID().toString();
    private final Type type;
    private final ArchiveSelection selection;
    private final Instant createdAt = Instant.now();
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile State state = State.QUEUED;
    private volatile String message = "";
    private volatile String currentFile;
    private final AtomicInteger candidates = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong bytesBefore = new AtomicLong();
    private final AtomicLong bytesAfter = new AtomicLong();
    private final ConcurrentLinkedDeque<String> errors = new ConcurrentLinkedDeque<>();
    private volatile boolean cancelRequested;

    public ArchiveJob(Type type, ArchiveSelection selection) {
        this.type = type;
        this.selection = selection;
    }

    public void markRunning() {
        startedAt = Instant.now();
        state = State.RUNNING;
    }

    public void finish(State finalState, String message) {
        finishedAt = Instant.now();
        state = finalState;
        this.message = message;
        currentFile = null;
    }

    public void setCandidates(int count) {
        candidates.set(count);
    }

    public void setCurrentFile(String fileName) {
        currentFile = fileName;
    }

    public void recordResult(ArchiveFileResult result) {
        processed.incrementAndGet();
        bytesBefore.addAndGet(result.bytesBefore());
        bytesAfter.addAndGet(result.bytesAfter());
        if (result.changed()) {
            succeeded.incrementAndGet();
        } else {
            skipped.incrementAndGet();
        }
    }

    public void recordError(String fileName, String error) {
        processed.incrementAndGet();
        failed.incrementAndGet();
        errors.addLast(fileName + ": " + error);
        while (errors.size() > MAX_ERRORS) {
            errors.pollFirst();
        }
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    @JsonIgnore
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    @JsonIgnore
    public boolean isActive() {
        return state == State.QUEUED || state == State.RUNNING;
    }

    public String getId() { return id; }
    public Type getType() { return type; }
    public ArchiveSelection getSelection() { return selection; }
    public State getState() { return state; }
    public String getMessage() { return message; }
    public String getCurrentFile() { return currentFile; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getCandidates() { return candidates.get(); }
    public int getProcessed() { return processed.get(); }
    public int getSucceeded() { return succeeded.get(); }
    public int getSkipped() { return skipped.get(); }
    public int getFailed() { return failed.get(); }
    public long getBytesBefore() { return bytesBefore.get(); }
    public long getBytesAfter() { return bytesAfter.get(); }
    public long getBytesSaved() { return bytesBefore.get() - bytesAfter.get(); }
    public boolean isDryRun() { return selection != null && selection.isDryRun(); }
    public List<String> getErrors() { return new ArrayList<>(errors); }

    public int getPercent() {
        int total = candidates.get();
        if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) {
            return 100;
        }
        return total == 0 ? 0 : (int) Math.min(99, (100L * processed.get()) / total);
    }
}
