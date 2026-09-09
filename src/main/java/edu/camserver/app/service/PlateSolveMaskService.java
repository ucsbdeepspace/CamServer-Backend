package edu.camserver.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.platesolve.PlateSolveCrop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlateSolveMaskService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, BufferedImage> staticMaskCache = new ConcurrentHashMap<>();
    private final boolean autoFisheyeMaskEnabled;
    private final double autoFisheyeUsableRadius;
    private final int autoFisheyeThreshold;
    private final boolean autoGlareMaskEnabled;
    private final int autoGlareThreshold;
    private final int autoGlareMinArea;
    private final int autoGlareDilationPx;
    private final boolean autoGlareBandMaskEnabled;
    private final int autoGlareBandThreshold;
    private final int autoGlareBandMinArea;
    private final int autoGlareBandDilationPx;
    private final double autoGlareBandRimRadius;
    private final boolean staticMaskEnabled;
    private final Path staticMaskDir;
    private final boolean yoloEnabled;
    private final String yoloUrl;
    private final Duration yoloTimeout;
    private final double yoloMinConfidence;
    private final Set<String> maskLabels;

    public PlateSolveMaskService(
            ObjectMapper objectMapper,
            @Value("${app.plate-solve.mask.auto-fisheye-enabled:true}") boolean autoFisheyeMaskEnabled,
            @Value("${app.plate-solve.mask.auto-fisheye-usable-radius:0.74}") double autoFisheyeUsableRadius,
            @Value("${app.plate-solve.mask.auto-fisheye-threshold:12}") int autoFisheyeThreshold,
            @Value("${app.plate-solve.mask.auto-glare-enabled:true}") boolean autoGlareMaskEnabled,
            @Value("${app.plate-solve.mask.auto-glare-threshold:210}") int autoGlareThreshold,
            @Value("${app.plate-solve.mask.auto-glare-min-area:80}") int autoGlareMinArea,
            @Value("${app.plate-solve.mask.auto-glare-dilation-px:64}") int autoGlareDilationPx,
            @Value("${app.plate-solve.mask.auto-glare-band-enabled:true}") boolean autoGlareBandMaskEnabled,
            @Value("${app.plate-solve.mask.auto-glare-band-threshold:60}") int autoGlareBandThreshold,
            @Value("${app.plate-solve.mask.auto-glare-band-min-area:500}") int autoGlareBandMinArea,
            @Value("${app.plate-solve.mask.auto-glare-band-dilation-px:72}") int autoGlareBandDilationPx,
            @Value("${app.plate-solve.mask.auto-glare-band-rim-radius:0.35}") double autoGlareBandRimRadius,
            @Value("${app.plate-solve.mask.static-enabled:true}") boolean staticMaskEnabled,
            @Value("${app.plate-solve.mask.static-dir:}") String staticMaskDir,
            @Value("${app.plate-solve.mask.yolo-enabled:false}") boolean yoloEnabled,
            @Value("${app.plate-solve.mask.yolo-url:}") String yoloUrl,
            @Value("${app.plate-solve.mask.yolo-timeout-ms:2500}") long yoloTimeoutMs,
            @Value("${app.plate-solve.mask.yolo-min-confidence:0.35}") double yoloMinConfidence,
            @Value("${app.plate-solve.mask.labels:cloud,tree,building,horizon,obstruction,antenna,glare}") String labels) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(250, yoloTimeoutMs)))
                .build();
        this.autoFisheyeMaskEnabled = autoFisheyeMaskEnabled;
        this.autoFisheyeUsableRadius = Math.max(0.5, Math.min(1.0, autoFisheyeUsableRadius));
        this.autoFisheyeThreshold = Math.max(0, Math.min(255, autoFisheyeThreshold));
        this.autoGlareMaskEnabled = autoGlareMaskEnabled;
        this.autoGlareThreshold = Math.max(0, Math.min(255, autoGlareThreshold));
        this.autoGlareMinArea = Math.max(4, autoGlareMinArea);
        this.autoGlareDilationPx = Math.max(0, autoGlareDilationPx);
        this.autoGlareBandMaskEnabled = autoGlareBandMaskEnabled;
        this.autoGlareBandThreshold = Math.max(0, Math.min(255, autoGlareBandThreshold));
        this.autoGlareBandMinArea = Math.max(16, autoGlareBandMinArea);
        this.autoGlareBandDilationPx = Math.max(0, autoGlareBandDilationPx);
        this.autoGlareBandRimRadius = Math.max(0.0, Math.min(1.0, autoGlareBandRimRadius));
        this.staticMaskEnabled = staticMaskEnabled;
        this.staticMaskDir = staticMaskDir == null || staticMaskDir.isBlank() ? null : Path.of(staticMaskDir);
        this.yoloEnabled = yoloEnabled;
        this.yoloUrl = yoloUrl == null ? "" : yoloUrl;
        this.yoloTimeout = Duration.ofMillis(Math.max(250, yoloTimeoutMs));
        this.yoloMinConfidence = Math.max(0, Math.min(1, yoloMinConfidence));
        this.maskLabels = parseLabels(labels);
    }

    public boolean[] buildIgnoreMask(BufferedImage source, Image image, PlateSolveCrop crop, Path sourcePath) {
        boolean[] ignoreMask = new boolean[crop.width() * crop.height()];
        FisheyeCircle circle = estimateFisheyeCircle(source);
        applyAutoFisheyeMask(ignoreMask, crop, circle);
        applyAutoGlareMask(ignoreMask, source, crop, circle);
        applyAutoGlareBandMask(ignoreMask, source, crop, circle);
        applyStaticMask(ignoreMask, crop, image);
        applyYoloMask(ignoreMask, crop, source, image, sourcePath);
        return ignoreMask;
    }

    private void applyAutoFisheyeMask(boolean[] ignoreMask, PlateSolveCrop crop, FisheyeCircle circle) {
        if (!autoFisheyeMaskEnabled) {
            return;
        }

        double usableRadius = circle.radius() * autoFisheyeUsableRadius;
        double usableRadiusSq = usableRadius * usableRadius;

        for (int y = 0; y < crop.height(); y++) {
            for (int x = 0; x < crop.width(); x++) {
                double sourceX = crop.x() + x + 0.5;
                double sourceY = crop.y() + y + 0.5;
                double dx = sourceX - circle.centerX();
                double dy = sourceY - circle.centerY();
                if (dx * dx + dy * dy > usableRadiusSq) {
                    ignoreMask[y * crop.width() + x] = true;
                }
            }
        }
    }

    private void applyAutoGlareMask(boolean[] ignoreMask, BufferedImage source, PlateSolveCrop crop, FisheyeCircle circle) {
        if (!autoGlareMaskEnabled) {
            return;
        }

        int width = crop.width();
        int height = crop.height();
        boolean[] bright = new boolean[width * height];
        boolean[] visited = new boolean[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!ignoreMask[index]
                        && luminance(source.getRGB(crop.x() + x, crop.y() + y)) >= autoGlareThreshold) {
                    bright[index] = true;
                }
            }
        }

        for (int index = 0; index < bright.length; index++) {
            if (!bright[index] || visited[index]) {
                continue;
            }

            GlareComponent component = collectGlareComponent(bright, visited, width, height, index);
            if (component.area() >= autoGlareMinArea) {
                maskGlareComponent(ignoreMask, crop, circle, component);
            }
        }
    }

    private void applyAutoGlareBandMask(boolean[] ignoreMask, BufferedImage source, PlateSolveCrop crop, FisheyeCircle circle) {
        if (!autoGlareBandMaskEnabled) {
            return;
        }

        int width = crop.width();
        int height = crop.height();
        boolean[] band = new boolean[width * height];
        boolean[] visited = new boolean[width * height];
        double rimRadius = circle.radius() * autoGlareBandRimRadius;
        double rimRadiusSq = rimRadius * rimRadius;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (ignoreMask[index]) {
                    continue;
                }

                int sourceX = crop.x() + x;
                int sourceY = crop.y() + y;
                double dx = sourceX + 0.5 - circle.centerX();
                double dy = sourceY + 0.5 - circle.centerY();
                if (dx * dx + dy * dy >= rimRadiusSq
                        && luminance(source.getRGB(sourceX, sourceY)) >= autoGlareBandThreshold) {
                    band[index] = true;
                }
            }
        }

        for (int index = 0; index < band.length; index++) {
            if (!band[index] || visited[index]) {
                continue;
            }

            GlareComponent component = collectGlareComponent(band, visited, width, height, index);
            if (component.area() >= autoGlareBandMinArea) {
                maskComponentRect(
                        ignoreMask,
                        crop,
                        component,
                        autoGlareBandDilationPx,
                        autoGlareBandDilationPx * 2
                );
            }
        }
    }

    private GlareComponent collectGlareComponent(
            boolean[] bright,
            boolean[] visited,
            int width,
            int height,
            int startIndex) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startIndex);
        visited[startIndex] = true;

        int area = 0;
        int minX = startIndex % width;
        int maxX = minX;
        int minY = startIndex / width;
        int maxY = minY;

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            area++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }

                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue;
                    }

                    int neighbor = ny * width + nx;
                    if (bright[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true;
                        queue.add(neighbor);
                    }
                }
            }
        }

        return new GlareComponent(area, minX, minY, maxX, maxY);
    }

    private void maskGlareComponent(
            boolean[] ignoreMask,
            PlateSolveCrop crop,
            FisheyeCircle circle,
            GlareComponent component) {
        double centerX = crop.x() + component.centerX();
        double centerY = crop.y() + component.centerY();
        double dx = centerX - circle.centerX();
        double dy = centerY - circle.centerY();
        boolean nearRim = Math.sqrt(dx * dx + dy * dy) > circle.radius() * 0.58;
        int dilationX = autoGlareDilationPx * (nearRim ? 2 : 1);
        int dilationY = autoGlareDilationPx * (nearRim ? 3 : 2);
        maskComponentRect(ignoreMask, crop, component, dilationX, dilationY);
    }

    private void maskComponentRect(
            boolean[] ignoreMask,
            PlateSolveCrop crop,
            GlareComponent component,
            int dilationX,
            int dilationY) {
        fillRect(
                ignoreMask,
                crop,
                crop.x() + component.minX() - dilationX,
                crop.y() + component.minY() - dilationY,
                component.width() + dilationX * 2,
                component.height() + dilationY * 2
        );
    }

    private FisheyeCircle estimateFisheyeCircle(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int step = Math.max(1, Math.min(width, height) / 900);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                if (luminance(source.getRGB(x, y)) > autoFisheyeThreshold) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX || maxY < minY) {
            double centerX = width / 2.0;
            double centerY = height / 2.0;
            double radius = Math.min(width, height) / 2.0;
            return new FisheyeCircle(centerX, centerY, radius);
        }

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double radiusX = (maxX - minX + step) / 2.0;
        double radiusY = (maxY - minY + step) / 2.0;
        double radius = Math.min(radiusX, radiusY);
        double maxCenteredRadius = Math.min(
                Math.min(centerX, width - centerX),
                Math.min(centerY, height - centerY)
        );
        return new FisheyeCircle(centerX, centerY, Math.max(1, Math.min(radius, maxCenteredRadius)));
    }

    private void applyStaticMask(boolean[] ignoreMask, PlateSolveCrop crop, Image image) {
        if (!staticMaskEnabled || staticMaskDir == null) {
            return;
        }

        Optional<BufferedImage> mask = loadStaticMask(image);
        if (mask.isEmpty()) {
            return;
        }

        BufferedImage maskImage = mask.get();

        for (int y = 0; y < crop.height(); y++) {
            for (int x = 0; x < crop.width(); x++) {
                int maskX = crop.x() + x;
                int maskY = crop.y() + y;
                if (isMasked(maskImage, maskX, maskY, crop.originalWidth(), crop.originalHeight())) {
                    ignoreMask[y * crop.width() + x] = true;
                }
            }
        }
    }

    private Optional<BufferedImage> loadStaticMask(Image image) {
        for (String maskName : maskCandidates(image)) {
            Path path = staticMaskDir.resolve(maskName);
            if (!Files.exists(path)) {
                continue;
            }

            try {
                BufferedImage cached = staticMaskCache.computeIfAbsent(path.toString(), ignored -> {
                    try {
                        return ImageIO.read(path.toFile());
                    } catch (IOException e) {
                        return null;
                    }
                });

                if (cached != null) {
                    return Optional.of(cached);
                }
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private List<String> maskCandidates(Image image) {
        List<String> candidates = new ArrayList<>();
        if (image.getCameraId() != null && !image.getCameraId().isBlank()) {
            candidates.add(sanitize(image.getCameraId()) + ".png");
            candidates.add(sanitize(image.getCameraId()) + ".jpg");
        }

        if (image.getSiteName() != null && !image.getSiteName().isBlank()) {
            candidates.add(sanitize(image.getSiteName()) + ".png");
            candidates.add(sanitize(image.getSiteName()) + ".jpg");
        }

        candidates.add("default.png");
        candidates.add("default.jpg");
        return candidates;
    }

    private boolean isMasked(BufferedImage maskImage, int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
        int x = Math.max(0, Math.min(maskImage.getWidth() - 1, sourceX * maskImage.getWidth() / Math.max(1, sourceWidth)));
        int y = Math.max(0, Math.min(maskImage.getHeight() - 1, sourceY * maskImage.getHeight() / Math.max(1, sourceHeight)));
        int rgb = maskImage.getRGB(x, y);
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red + green + blue) / 3 > 127;
    }

    private void applyYoloMask(boolean[] ignoreMask, PlateSolveCrop crop, BufferedImage source, Image image, Path sourcePath) {
        if (!yoloEnabled || yoloUrl.isBlank()) {
            return;
        }

        try {
            JsonNode root = requestYoloMask(source, image, sourcePath);
            JsonNode objects = root.path("objects");
            if (!objects.isArray()) {
                return;
            }

            for (JsonNode object : objects) {
                String label = object.path("label").asText("").toLowerCase();
                double confidence = object.path("confidence").asDouble(1.0);
                if (!shouldMaskLabel(label) || confidence < yoloMinConfidence) {
                    continue;
                }

                applyDetection(ignoreMask, crop, object);
            }
        } catch (Exception ignored) {
            // Masking is best-effort. The solver still runs with static masks and point-source filtering.
        }
    }

    private JsonNode requestYoloMask(BufferedImage source, Image image, Path sourcePath) throws IOException, InterruptedException {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("imgId", image.getImgId());
        requestBody.put("cameraId", image.getCameraId());
        requestBody.put("siteName", image.getSiteName());
        requestBody.put("imagePath", sourcePath.toString());
        requestBody.put("width", source.getWidth());
        requestBody.put("height", source.getHeight());

        HttpRequest request = HttpRequest.newBuilder(URI.create(yoloUrl))
                .timeout(yoloTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("YOLO mask service returned HTTP " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    private void applyDetection(boolean[] ignoreMask, PlateSolveCrop crop, JsonNode object) {
        JsonNode polygon = object.path("polygon");
        if (polygon.isArray() && polygon.size() >= 3) {
            List<Point> points = new ArrayList<>();
            for (JsonNode point : polygon) {
                if (point.isArray() && point.size() >= 2) {
                    points.add(new Point(point.get(0).asInt(), point.get(1).asInt()));
                } else {
                    points.add(new Point(point.path("x").asInt(), point.path("y").asInt()));
                }
            }
            fillPolygon(ignoreMask, crop, points);
            return;
        }

        JsonNode bbox = object.path("bbox");
        if (!bbox.isMissingNode()) {
            int x = bbox.path("x").asInt();
            int y = bbox.path("y").asInt();
            int width = bbox.path("width").asInt();
            int height = bbox.path("height").asInt();
            fillRect(ignoreMask, crop, x, y, width, height);
        }
    }

    private void fillRect(boolean[] ignoreMask, PlateSolveCrop crop, int x, int y, int width, int height) {
        int startX = Math.max(crop.x(), x) - crop.x();
        int startY = Math.max(crop.y(), y) - crop.y();
        int endX = Math.min(crop.x() + crop.width(), x + width) - crop.x();
        int endY = Math.min(crop.y() + crop.height(), y + height) - crop.y();

        for (int cy = Math.max(0, startY); cy < Math.min(crop.height(), endY); cy++) {
            for (int cx = Math.max(0, startX); cx < Math.min(crop.width(), endX); cx++) {
                ignoreMask[cy * crop.width() + cx] = true;
            }
        }
    }

    private void fillPolygon(boolean[] ignoreMask, PlateSolveCrop crop, List<Point> points) {
        int minX = points.stream().mapToInt(point -> point.x).min().orElse(0);
        int maxX = points.stream().mapToInt(point -> point.x).max().orElse(0);
        int minY = points.stream().mapToInt(point -> point.y).min().orElse(0);
        int maxY = points.stream().mapToInt(point -> point.y).max().orElse(0);
        int startX = Math.max(crop.x(), minX) - crop.x();
        int endX = Math.min(crop.x() + crop.width() - 1, maxX) - crop.x();
        int startY = Math.max(crop.y(), minY) - crop.y();
        int endY = Math.min(crop.y() + crop.height() - 1, maxY) - crop.y();

        for (int y = Math.max(0, startY); y <= Math.min(crop.height() - 1, endY); y++) {
            for (int x = Math.max(0, startX); x <= Math.min(crop.width() - 1, endX); x++) {
                if (contains(points, crop.x() + x, crop.y() + y)) {
                    ignoreMask[y * crop.width() + x] = true;
                }
            }
        }
    }

    private boolean contains(List<Point> polygon, int x, int y) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point pi = polygon.get(i);
            Point pj = polygon.get(j);
            if ((pi.y > y) != (pj.y > y)
                    && x < (double) (pj.x - pi.x) * (y - pi.y) / Math.max(1, pj.y - pi.y) + pi.x) {
                inside = !inside;
            }
        }
        return inside;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean shouldMaskLabel(String label) {
        return maskLabels.stream().anyMatch(maskLabel ->
                label.equals(maskLabel) || label.contains(maskLabel) || maskLabel.contains(label)
        );
    }

    private int luminance(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private Set<String> parseLabels(String labels) {
        Set<String> parsed = new HashSet<>();
        Arrays.stream(labels.split(","))
                .map(String::trim)
                .filter(label -> !label.isEmpty())
                .map(String::toLowerCase)
                .forEach(parsed::add);
        return parsed;
    }

    private record FisheyeCircle(double centerX, double centerY, double radius) {
    }

    private record GlareComponent(int area, int minX, int minY, int maxX, int maxY) {
        private int width() {
            return maxX - minX + 1;
        }

        private int height() {
            return maxY - minY + 1;
        }

        private double centerX() {
            return (minX + maxX) / 2.0;
        }

        private double centerY() {
            return (minY + maxY) / 2.0;
        }
    }
}
