package edu.camserver.app.model.platesolve;

import java.time.Instant;
import java.util.List;

public record PlateSolveResult(
        long imgId,
        PlateSolveStatus status,
        String message,
        boolean cached,
        Instant generatedAt,
        PlateSolveCrop crop,
        PlateSolveSolution solution,
        PlateSolveProgress progress,
        List<PlateSolveStar> stars
) {
    public PlateSolveResult withCached(boolean cached) {
        return new PlateSolveResult(imgId, status, message, cached, generatedAt, crop, solution, progress, stars);
    }
}
