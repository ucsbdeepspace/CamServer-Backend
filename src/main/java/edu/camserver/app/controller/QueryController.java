package edu.camserver.app.controller;

import edu.camserver.app.model.Camera;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.ImageFilter;
import edu.camserver.app.model.archive.StoredImage;
import edu.camserver.app.service.CameraService;
import edu.camserver.app.service.ImageArchiveService;
import edu.camserver.app.service.ImageService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
public class QueryController {

    private static final MediaType APPLICATION_GZIP = MediaType.parseMediaType("application/gzip");

    private final ImageService imageService;
    private final CameraService cameraService;
    private final ImageArchiveService archiveService;

    public QueryController(ImageService imageService, CameraService cameraService, ImageArchiveService archiveService) {
        this.imageService = imageService;
        this.cameraService = cameraService;
        this.archiveService = archiveService;
    }

    @GetMapping("/query")
    public List<Image> query(
            @RequestParam(defaultValue="20") int pagesize,
            @RequestParam(required = false) String lastUID,
            @RequestParam(required = false) Boolean featured,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime startDate,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) LocalDateTime endDate,
            @RequestParam(required = false) String siteName,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String period) {

        ImageFilter filter = new ImageFilter(featured, startDate, endDate, siteName, search, period);
        return imageService.findAll(pagesize, lastUID, filter);
    }

    @GetMapping("/query/{imgId}")
    public ResponseEntity<Image> image(@PathVariable long imgId) {
        try {
            return ResponseEntity.ok(imageService.findById(imgId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Serves an image file regardless of how it is stored on disk: plain, whole-file gzip
     * ({@code .gz}) or FITS tile compression ({@code .fz}).
     *
     * <ul>
     *   <li>{@code frame.fits}: the plain frame. Stored as .gz it is passed through with
     *       {@code Content-Encoding: gzip} when the client accepts it, otherwise inflated; stored
     *       as .fz it is decoded on the fly.</li>
     *   <li>{@code frame.fits.gz}: the gzip form, compressed on the fly unless stored that way.</li>
     *   <li>{@code frame.fits.fz}: the Rice-compressed FITS exactly as stored (it is a valid FITS
     *       file); 404 when the frame is not stored that way.</li>
     * </ul>
     */
    @GetMapping("/images/{fileName:.+}")
    public ResponseEntity<Resource> getFile(@PathVariable String fileName,
                                            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
                                            HttpServletResponse response) throws IOException {
        Optional<StoredImage> located;
        try {
            located = archiveService.locate(fileName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (located.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StoredImage stored = located.get();
        StoredImage.Format wanted = StoredImage.Format.ofFileName(fileName.trim());
        String downloadName = stored.logicalName() + wanted.suffix();
        MediaType logicalType = archiveService.mediaTypeFor(stored.logicalName());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline().filename(downloadName).build());
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic());

        if (wanted == StoredImage.Format.RICE) {
            if (stored.format() != StoredImage.Format.RICE) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok().headers(headers).contentType(logicalType)
                    .body(new FileSystemResource(stored.path()));
        }

        if (wanted == StoredImage.Format.GZIP) {
            if (stored.gzipped()) {
                return ResponseEntity.ok().headers(headers).contentType(APPLICATION_GZIP)
                        .body(new FileSystemResource(stored.path()));
            }
            streamInline(response, headers, APPLICATION_GZIP, out -> archiveService.writeGzipped(stored, out));
            return null;
        }

        switch (stored.format()) {
            case PLAIN -> {
                return ResponseEntity.ok().headers(headers).contentType(logicalType)
                        .body(new FileSystemResource(stored.path()));
            }
            case GZIP -> {
                if (acceptsGzip(acceptEncoding)) {
                    headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
                    headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
                    return ResponseEntity.ok().headers(headers).contentType(logicalType)
                            .body(new FileSystemResource(stored.path()));
                }
            }
            case RICE -> {
                // decoded below
            }
        }

        streamInline(response, headers, logicalType, out -> {
            try (InputStream in = archiveService.openDecompressed(stored)) {
                in.transferTo(out);
            }
        });
        return null;
    }

    private interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Writes a body whose length is not known up front straight to the response. Returning
     * {@code null} from the handler afterwards tells Spring the response is already complete.
     */
    private static void streamInline(HttpServletResponse response, HttpHeaders headers, MediaType type,
                                     BodyWriter writer) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setContentType(type.toString());
        try (OutputStream out = response.getOutputStream()) {
            writer.write(out);
            out.flush();
        }
    }

    private static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String token : acceptEncoding.split(",")) {
            String[] parts = token.trim().split(";");
            String coding = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!coding.equals("gzip") && !coding.equals("*")) {
                continue;
            }
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim().replace(" ", "");
                if (param.startsWith("q=")) {
                    try {
                        if (Double.parseDouble(param.substring(2)) <= 0) {
                            return false;
                        }
                    } catch (NumberFormatException ignored) {
                        // malformed q-value: treat as accepted
                    }
                }
            }
            return true;
        }
        return false;
    }

    @GetMapping("/sites")
    public List<Camera> sites() { return cameraService.getSites(); }

}
