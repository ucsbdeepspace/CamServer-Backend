package edu.camserver.app.controller;

import edu.camserver.app.config.ImagePaths;
import edu.camserver.app.model.Image;
import edu.camserver.app.service.CaptureTimeZones;
import edu.camserver.app.service.ImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Receives frames from the camera scripts.
 *
 * <p>Two generations of form-field names are in use. The Raspberry Pi scripts post
 * {@code id, name, date, bit, gain, exp, temp, hum, tz, isDay} (plus {@code lat}/{@code lng}, which
 * have no column and are ignored); newer clients use the entity names {@code camId, siteName, date,
 * bit, gain, exposure, temperature, humidity, timeZone, isDayTime}. Both vocabularies are accepted
 * so the deployed cameras keep working without a script change.
 *
 * <p>{@code date} is the capture time. Current scripts send an ISO-8601 date-time with a UTC
 * offset ({@code 2026-09-05T05:35:12.123+00:00}), which is stored as that instant. A value without
 * an offset is taken as the convention of the original Pi scripts, local wall-clock time plus seven
 * hours, and converted through the upload's {@code tz} (see {@link CaptureTimeZones}).
 *
 * <p>Every file is stored under its original name in the image directory. Only JPEG uploads create a
 * database row; the matching FITS upload shares the row through the extension-less {@code ImgPath}.
 */
@RestController
@RequestMapping("/api")
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final ImagePaths imagePaths;
    private final ImageService imageService;
    private final CaptureTimeZones timeZones;

    public UploadController(ImagePaths imagePaths, ImageService imageService, CaptureTimeZones timeZones) {
        this.imagePaths = imagePaths;
        this.imageService = imageService;
        this.timeZones = timeZones;
    }

    @PostMapping("/upload_image")
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file,
                                         @RequestParam Map<String, String> form) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return badRequest("Missing filename");
        }

        Fields fields = new Fields(form);
        String camId = fields.text("camId", "id");
        String siteName = fields.text("siteName", "name"); // informational; the site comes from Cameras
        String timeZone = fields.text("timeZone", "tz");
        Instant timestamp = fields.instant(timeZones.resolve(timeZone), "date", "timestamp");
        Integer bit = fields.integer("bit", "bitDepth");
        Integer gain = fields.integer("gain");
        Integer exposure = fields.integer("exposure", "exp", "expTime");
        Float temperature = fields.decimal("temperature", "temp");
        Float humidity = fields.decimal("humidity", "hum");
        Boolean dayTime = fields.flag("isDayTime", "isDay");
        if (!fields.problems.isEmpty()) {
            log.warn("Rejected upload {} from {}: {}", filename, camId, fields.problems);
            return badRequest(String.join("; ", fields.problems));
        }

        File dest;
        try {
            dest = imagePaths.fileFor(filename);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            file.transferTo(dest);
        } catch (IOException e) {
            log.error("Failed to store {}: {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }

        Long imgId = null;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            String imgPath = stripExtension(dest.getAbsolutePath());
            Image image = new Image(camId, siteName, timestamp, bit, gain, exposure, imgPath,
                    temperature, humidity, timeZone, false);
            image.setIsDayTime(dayTime);
            try {
                imgId = imageService.save(image).getImgId();
            } catch (Exception e) {
                log.error("Stored {} but the database insert failed", filename, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Database insert failed: " + e.getMessage()));
            }
        }

        log.info("Upload {} ({} bytes) from {}{}", filename, file.getSize(), camId,
                imgId == null ? "" : " -> image " + imgId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "File " + filename + " uploaded");
        if (imgId != null) {
            body.put("imgId", imgId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** Drops the final extension of the last path segment only. */
    private static String stripExtension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf(File.separatorChar);
        return dot > slash ? path.substring(0, dot) : path;
    }

    /**
     * Looks each value up under its alternative field names and collects what is missing or
     * malformed, so a rejected upload reports every problem at once instead of the first.
     */
    private static final class Fields {
        private final Map<String, String> form;
        private final List<String> problems = new ArrayList<>();

        Fields(Map<String, String> form) {
            this.form = form;
        }

        String text(String... names) {
            for (String name : names) {
                String value = form.get(name);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            problems.add("missing " + String.join("/", names));
            return null;
        }

        Integer integer(String... names) {
            String raw = text(names);
            if (raw == null) {
                return null;
            }
            try {
                // Some scripts format whole numbers as "60.0".
                return Math.toIntExact(Math.round(Double.parseDouble(raw)));
            } catch (NumberFormatException | ArithmeticException e) {
                problems.add(names[0] + " is not a whole number: " + raw);
                return null;
            }
        }

        Float decimal(String... names) {
            String raw = text(names);
            if (raw == null) {
                return null;
            }
            try {
                return Float.parseFloat(raw);
            } catch (NumberFormatException e) {
                problems.add(names[0] + " is not a number: " + raw);
                return null;
            }
        }

        /**
         * An ISO-8601 date-time. With a UTC offset it is the instant itself; without one it is a
         * legacy "local + 7 h" value from a camera in {@code legacyZone}.
         */
        Instant instant(ZoneId legacyZone, String... names) {
            String raw = text(names);
            if (raw == null) {
                return null;
            }
            try {
                return CaptureTimeZones.parseUploadTime(raw, legacyZone);
            } catch (DateTimeException e) {
                problems.add(names[0] + " is not an ISO-8601 date-time: " + raw);
                return null;
            }
        }

        /** Optional flag: absent or blank gives {@code null}; accepts 1/0, true/false, yes/no. */
        Boolean flag(String... names) {
            for (String name : names) {
                String value = form.get(name);
                if (value == null || value.isBlank()) {
                    continue;
                }
                switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "1", "true", "yes" -> {
                        return true;
                    }
                    case "0", "false", "no" -> {
                        return false;
                    }
                    default -> {
                        problems.add(name + " is not a boolean: " + value);
                        return null;
                    }
                }
            }
            return null;
        }
    }
}
