package edu.camserver.app.controller;

import edu.camserver.app.model.FrameMeta;
import edu.camserver.app.service.FrameService;
import edu.camserver.app.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Legacy telemetry endpoint kept for older camera scripts that post a JPEG per frame. The image
 * itself is no longer stored or streamed: live video is carried by {@link LiveStreamController}
 * as fragmented MP4. The timestamp and position fields still feed the seeing-monitor page.
 */
@RestController
public class StreamController {

    private final FrameService frameService;
    private final SettingsService settingsService;

    public StreamController(FrameService frameService, SettingsService settingsService) {
        this.frameService = frameService;
        this.settingsService = settingsService;
    }

    @PostMapping("/upload_live")
    public ResponseEntity<Map<String, Object>> uploadFrame(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "ts", required = false) String tsStr,
            @RequestParam(value = "pos", required = false) String pos) {
        FrameMeta meta = frameService.recordClientTimestamp(tsStr);
        frameService.updatePos(pos);

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("settings", settingsService.getSettings());
        resp.put("latency_ms", meta.getLatencyMs());
        resp.put("video", "JPEG frames are not streamed any more; push fragmented MP4 to /api/live/ingest");
        return ResponseEntity.ok(resp);
    }
}
