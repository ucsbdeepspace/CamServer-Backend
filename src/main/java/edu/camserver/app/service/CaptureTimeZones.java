package edu.camserver.app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;

/**
 * Time-zone rules shared by everything that turns a camera's wall-clock time into an instant.
 *
 * <p>Image rows carry the site's IANA zone name in {@code TimeZone}. Rows without one, or with a
 * name Java does not know, belong to the default zone ({@code app.images.default-time-zone}),
 * which covers every camera deployed so far.
 */
@Component
public class CaptureTimeZones {

    /**
     * The original Pi upload scripts post {@code date} as local wall-clock time plus a hard-coded
     * seven hours ({@code datetime.now() + timedelta(hours=7)}) and no UTC offset, all year round.
     * Every database row written before {@link TimestampUtcMigration} ran follows the same rule.
     */
    public static final Duration LEGACY_UPLOAD_OFFSET = Duration.ofHours(7);

    private final ZoneId defaultZone;

    public CaptureTimeZones(@Value("${app.images.default-time-zone:America/Los_Angeles}") String defaultZone) {
        this.defaultZone = ZoneId.of(defaultZone);
    }

    public ZoneId defaultZone() {
        return defaultZone;
    }

    /** The zone a stored {@code TimeZone} value names, or the default zone when it is blank or unknown. */
    public ZoneId resolve(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(timeZone.trim(), ZoneId.SHORT_IDS);
        } catch (DateTimeException e) {
            return defaultZone;
        }
    }

    /**
     * The capture time a script sent. An ISO-8601 date-time with a UTC offset
     * ({@code 2026-09-05T05:35:12.123+00:00}) is the instant itself; one without an offset is a
     * legacy "local + 7 h" value from a camera in {@code legacyZone}.
     *
     * @throws DateTimeException when {@code raw} is not an ISO-8601 date-time
     */
    public static Instant parseUploadTime(String raw, ZoneId legacyZone) {
        TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parse(raw.trim().replace(' ', 'T'));
        if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
            return OffsetDateTime.from(parsed).toInstant();
        }
        return fromLegacyUpload(LocalDateTime.from(parsed), legacyZone);
    }

    /** The instant a legacy "local + 7 h" value from a camera in {@code zone} denotes. */
    public static Instant fromLegacyUpload(LocalDateTime value, ZoneId zone) {
        return value.minus(LEGACY_UPLOAD_OFFSET).atZone(zone).toInstant();
    }

    /** Wall-clock time {@code local} in {@code zone}, expressed as UTC wall-clock time (the column's form). */
    public static LocalDateTime toUtc(LocalDateTime local, ZoneId zone) {
        return LocalDateTime.ofInstant(local.atZone(zone).toInstant(), ZoneOffset.UTC);
    }
}
