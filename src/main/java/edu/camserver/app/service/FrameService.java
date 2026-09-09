package edu.camserver.app.service;

import edu.camserver.app.model.FrameMeta;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Latest telemetry reported by the seeing-monitor camera host: when its last report was taken,
 * how far behind the server clock that was, the star position it measured, and any extra fields
 * it chose to send (exposure, gain, encoder, ...).
 */
@Service
public class FrameService {
    private static final String SECONDS_FRACTION_FORMAT = "%S.%f";

    private final ReentrantLock lock = new ReentrantLock();
    private FrameMeta latestMeta = new FrameMeta();
    private String pos;
    private Map<String, Object> extras = Map.of();
    private Instant updatedAt;

    /**
     * Records the client-side timestamp of a report and derives the transport latency.
     *
     * <p>Accepts either seconds-with-fraction within the current minute ({@code "%S.%f"}, e.g.
     * {@code "07.123456"}), which is snapped to the nearest minute on the server clock so an
     * unsynchronised camera clock does not matter, or an absolute Unix epoch in seconds.
     */
    public FrameMeta recordClientTimestamp(String value) {
        double serverTs = System.currentTimeMillis() / 1000.0;
        FrameMeta meta = parse(value, serverTs);
        lock.lock();
        try {
            latestMeta = meta;
            updatedAt = Instant.now();
        } finally {
            lock.unlock();
        }
        return meta;
    }

    public void updatePos(String pos) {
        lock.lock();
        try {
            this.pos = pos;
            this.updatedAt = Instant.now();
        } finally {
            lock.unlock();
        }
    }

    public void updateExtras(Map<String, Object> extras) {
        lock.lock();
        try {
            this.extras = extras == null ? Map.of() : Map.copyOf(extras);
        } finally {
            lock.unlock();
        }
    }

    public FrameMeta getLatestMeta() {
        lock.lock();
        try {
            return latestMeta;
        } finally {
            lock.unlock();
        }
    }

    public String getPosMeta() {
        lock.lock();
        try {
            return pos;
        } finally {
            lock.unlock();
        }
    }

    /** Everything the seeing-monitor page shows next to the video, in one map. */
    public Map<String, Object> telemetry() {
        lock.lock();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clientTs", latestMeta.getClientTs());
            out.put("serverTs", latestMeta.getServerTs());
            out.put("latencyMs", latestMeta.getLatencyMs());
            out.put("pos", pos);
            out.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
            out.put("ageSeconds", updatedAt == null ? null
                    : Math.round((System.currentTimeMillis() - updatedAt.toEpochMilli()) / 100.0) / 10.0);
            out.put("extras", extras);
            return out;
        } finally {
            lock.unlock();
        }
    }

    private static FrameMeta parse(String value, double serverTs) {
        if (value == null || value.isBlank()) {
            return new FrameMeta(serverTs, serverTs, 0, SECONDS_FRACTION_FORMAT);
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return new FrameMeta(serverTs, serverTs, 0, SECONDS_FRACTION_FORMAT);
        }

        if (parsed >= 60.0) {
            int latencyMs = (int) Math.round(Math.max(0.0, serverTs - parsed) * 1000.0);
            return new FrameMeta(parsed, serverTs, latencyMs, "epoch");
        }

        double seconds = parsed % 60.0;
        double minuteFloor = serverTs - (serverTs % 60.0);
        double[] candidates = {
                minuteFloor - 60.0 + seconds,
                minuteFloor + seconds,
                minuteFloor + 60.0 + seconds
        };
        double clientEpoch = Arrays.stream(candidates)
                .boxed()
                .min(Comparator.comparingDouble(candidate -> Math.abs(candidate - serverTs)))
                .orElse(serverTs);
        int latencyMs = (int) Math.round(Math.max(0.0, serverTs - clientEpoch) * 1000.0);
        return new FrameMeta(clientEpoch, serverTs, latencyMs, SECONDS_FRACTION_FORMAT);
    }
}
