package edu.camserver.app.model.platesolve;

import java.time.Instant;
import java.util.List;

public record PlateSolveProgress(
        String phase,
        int percent,
        String detail,
        List<String> logTail,
        Instant startedAt,
        Instant updatedAt,
        String solverCommand
) {
}
