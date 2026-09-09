package edu.camserver.app.controller;

import edu.camserver.app.live.FragmentInfo;
import edu.camserver.app.live.Mp4BoxReader;
import edu.camserver.app.live.Mp4Parser;
import edu.camserver.app.live.TrackInfo;
import edu.camserver.app.model.FrameMeta;
import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.LiveStreamService;
import edu.camserver.app.service.SettingsService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Live video for the seeing monitor.
 *
 * <ul>
 *   <li>{@code POST /api/live/ingest} — the camera host streams fragmented MP4 (H.264) here in one
 *       long-lived chunked request, typically {@code ffmpeg ... -f mp4 -movflags
 *       frag_keyframe+empty_moov+default_base_moof}. Protected by {@code app.live.ingest-token}.</li>
 *   <li>{@code GET /api/live/stream.mp4} — viewers receive the initialization segment followed by
 *       fragments as they arrive; the browser feeds this into Media Source Extensions.</li>
 *   <li>{@code GET /api/live/status} — stream, telemetry and camera settings in one call.</li>
 *   <li>{@code POST /api/live/telemetry} — timestamp/position side channel from the camera host,
 *       which also returns the exposure/gain requested through the site.</li>
 * </ul>
 */
@RestController
public class LiveStreamController {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamController.class);
    private static final String TOKEN_HEADER = "X-Live-Token";

    private final LiveStreamService live;
    private final FrameService frameService;
    private final SettingsService settingsService;
    private final byte[] ingestToken;
    private final int maxBoxBytes;

    public LiveStreamController(LiveStreamService live,
                                FrameService frameService,
                                SettingsService settingsService,
                                @Value("${app.live.ingest-token:}") String ingestToken,
                                @Value("${app.live.max-box-bytes:33554432}") int maxBoxBytes) {
        this.live = live;
        this.frameService = frameService;
        this.settingsService = settingsService;
        this.ingestToken = ingestToken == null || ingestToken.isBlank()
                ? null
                : ingestToken.trim().getBytes(StandardCharsets.UTF_8);
        this.maxBoxBytes = maxBoxBytes;
        if (this.ingestToken == null) {
            log.warn("app.live.ingest-token is empty: anyone who can reach this server may publish the live stream");
        }
    }

    @PostMapping("/api/live/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            HttpServletRequest request,
            @RequestHeader(name = TOKEN_HEADER, required = false) String headerToken,
            @RequestParam(name = "token", required = false) String queryToken,
            @RequestHeader(name = "X-Live-Producer", required = false) String producer) {
        if (!authorized(headerToken, queryToken)) {
            return unauthorized();
        }

        LiveStreamService.Session session = live.openSession(producer, request.getRemoteAddr());
        String endReason = "producer closed the connection";
        try (InputStream in = request.getInputStream()) {
            Mp4BoxReader reader = new Mp4BoxReader(in, maxBoxBytes);
            byte[] ftyp = null;
            byte[] pendingMoof = null;
            Mp4BoxReader.Box box;
            while ((box = reader.next()) != null) {
                if (!live.isCurrent(session)) {
                    endReason = "replaced by a newer producer connection";
                    break;
                }
                switch (box.type()) {
                    case "ftyp" -> ftyp = box.bytes();
                    case "moov" -> {
                        TrackInfo track = Mp4Parser.parseTrack(box.bytes());
                        live.publishInit(session, concat(ftyp, box.bytes()), track);
                    }
                    case "moof" -> pendingMoof = box.bytes();
                    case "mdat" -> {
                        if (pendingMoof != null && session.track() != null) {
                            FragmentInfo info = Mp4Parser.parseFragment(pendingMoof, session.track());
                            live.publishFragment(session, concat(pendingMoof, box.bytes()), info);
                        }
                        pendingMoof = null;
                    }
                    default -> {
                        // styp, sidx, prft, free ... carry nothing a viewer needs.
                    }
                }
            }
        } catch (IOException e) {
            endReason = "connection lost: " + e.getMessage();
        } catch (RuntimeException e) {
            endReason = "rejected stream: " + e.getMessage();
            log.warn("Live stream: session {} rejected: {}", session.id(), e.toString());
        } finally {
            live.closeSession(session, endReason);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sessionId", session.id());
        body.put("fragments", session.fragments());
        body.put("bytes", session.bytes());
        body.put("ended", endReason);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/live/stream.mp4")
    public void stream(HttpServletResponse response) throws IOException {
        Optional<LiveStreamService.Viewer> attached = live.subscribe();
        response.setHeader("Cache-Control", "no-store, no-transform");
        if (attached.isEmpty()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader("Retry-After", "3");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"live\":false,\"message\":\"No camera is streaming right now.\"}");
            return;
        }

        LiveStreamService.Viewer viewer = attached.get();
        TrackInfo track = viewer.session().track();
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("video/mp4");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Live-Session", Long.toString(viewer.session().id()));
        if (track != null) {
            response.setHeader("X-Live-Codecs", track.codecs());
        }
        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(viewer.init());
            for (byte[] fragment : viewer.backlog()) {
                out.write(fragment);
            }
            out.flush();
            while (true) {
                byte[] chunk = viewer.queue().poll(5, TimeUnit.SECONDS);
                if (chunk == null) {
                    if (!viewer.isActive()) {
                        break;
                    }
                    continue;
                }
                if (chunk == LiveStreamService.END_OF_STREAM) {
                    break;
                }
                out.write(chunk);
                out.flush();
            }
        } catch (IOException e) {
            // The viewer went away; nothing to report.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            live.unsubscribe(viewer);
        }
    }

    @GetMapping("/api/live/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>(live.status());
        status.put("telemetry", frameService.telemetry());
        status.put("settings", settingsService.getSettings());
        return status;
    }

    @PostMapping("/api/live/telemetry")
    public ResponseEntity<Map<String, Object>> telemetry(
            @RequestHeader(name = TOKEN_HEADER, required = false) String headerToken,
            @RequestParam(name = "token", required = false) String queryToken,
            @RequestBody Map<String, Object> payload) {
        if (!authorized(headerToken, queryToken)) {
            return unauthorized();
        }
        Object ts = payload.get("ts");
        FrameMeta meta = frameService.recordClientTimestamp(ts == null ? null : String.valueOf(ts));
        Object pos = payload.get("pos");
        if (pos != null) {
            frameService.updatePos(String.valueOf(pos));
        }
        Map<String, Object> extras = new LinkedHashMap<>(payload);
        extras.remove("ts");
        extras.remove("pos");
        frameService.updateExtras(extras);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("latencyMs", meta.getLatencyMs());
        body.put("settings", settingsService.getSettings());
        return ResponseEntity.ok(body);
    }

    private boolean authorized(String headerToken, String queryToken) {
        if (ingestToken == null) {
            return true;
        }
        String provided = headerToken != null && !headerToken.isBlank() ? headerToken : queryToken;
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(ingestToken, provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("ok", false, "error", "missing or wrong " + TOKEN_HEADER));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        if (first == null) {
            return second;
        }
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
