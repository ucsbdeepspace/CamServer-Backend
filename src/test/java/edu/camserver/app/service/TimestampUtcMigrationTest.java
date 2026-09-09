package edu.camserver.app.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimestampUtcMigrationTest {

    @Test
    void readsTheLocalCaptureTimeFromTheFileName() {
        assertEquals(LocalDateTime.parse("2026-09-04T22:58:55.916"),
                TimestampUtcMigration.captureTimeFromName(
                        "/mnt/CamData/images/QHY5III678M-54ffe941916d2aa46_2026-09-04T22:58:55.916"));
        assertEquals(LocalDateTime.parse("2025-11-24T01:04:52"),
                TimestampUtcMigration.captureTimeFromName("QHY5III678C-57bbd14782e9f938e_2025-11-24T01:04:52 "));
    }

    @Test
    void namesWithoutACaptureTimeGiveNothing() {
        assertNull(TimestampUtcMigration.captureTimeFromName("/mnt/CamData/images/PolarisData"));
        assertNull(TimestampUtcMigration.captureTimeFromName("frame_2026-13-40T99:00:00"));
        assertNull(TimestampUtcMigration.captureTimeFromName(null));
    }
}
