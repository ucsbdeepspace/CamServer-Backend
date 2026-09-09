package edu.camserver.app.service;

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureTimeZonesTest {
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private final CaptureTimeZones zones = new CaptureTimeZones("America/Los_Angeles");

    @Test
    void legacyValuesAreLocalTimePlusSevenHoursInEverySeason() {
        // Pacific daylight time (UTC-7): the legacy digits happen to be UTC already.
        assertEquals(Instant.parse("2026-09-05T05:35:12Z"),
                CaptureTimeZones.fromLegacyUpload(LocalDateTime.parse("2026-09-05T05:35:12"), LOS_ANGELES));
        // Pacific standard time (UTC-8): local 13:44 is 21:44 UTC, an hour past the legacy digits.
        assertEquals(Instant.parse("2025-12-20T21:44:00Z"),
                CaptureTimeZones.fromLegacyUpload(LocalDateTime.parse("2025-12-20T20:44:00"), LOS_ANGELES));
    }

    @Test
    void uploadTimesWithAnOffsetAreTakenAsIsAndOthersAsLegacy() {
        assertEquals(Instant.parse("2026-09-05T05:35:12.123Z"),
                CaptureTimeZones.parseUploadTime("2026-09-05T05:35:12.123+00:00", LOS_ANGELES));
        assertEquals(Instant.parse("2026-09-05T05:35:12Z"),
                CaptureTimeZones.parseUploadTime("2026-09-05T05:35:12Z", LOS_ANGELES));
        assertEquals(Instant.parse("2026-09-04T22:35:12Z"),
                CaptureTimeZones.parseUploadTime("2026-09-05T00:35:12+02:00", LOS_ANGELES));
        assertEquals(Instant.parse("2025-12-20T21:44:00Z"),
                CaptureTimeZones.parseUploadTime("2025-12-20 20:44:00", LOS_ANGELES));
        assertThrows(DateTimeException.class, () -> CaptureTimeZones.parseUploadTime("yesterday", LOS_ANGELES));
    }

    @Test
    void blankAndUnknownZoneNamesFallBackToTheDefault() {
        assertEquals(LOS_ANGELES, zones.defaultZone());
        assertEquals(LOS_ANGELES, zones.resolve(null));
        assertEquals(LOS_ANGELES, zones.resolve("  "));
        assertEquals(LOS_ANGELES, zones.resolve("Mars/Olympus_Mons"));
        assertEquals(LOS_ANGELES, zones.resolve("PST"));
        assertEquals(ZoneId.of("Europe/Berlin"), zones.resolve(" Europe/Berlin "));
    }

    @Test
    void localBoundsBecomeUtcWithTheOffsetOfThatDate() {
        assertEquals(LocalDateTime.parse("2026-09-04T07:00:00"),
                CaptureTimeZones.toUtc(LocalDateTime.parse("2026-09-04T00:00:00"), LOS_ANGELES));
        assertEquals(LocalDateTime.parse("2026-01-05T07:59:59"),
                CaptureTimeZones.toUtc(LocalDateTime.parse("2026-01-04T23:59:59"), LOS_ANGELES));
    }
}
