package edu.camserver.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time conversion of {@code Images.Timestamp} from the legacy upload convention (site local
 * time + 7 h, see {@link CaptureTimeZones#LEGACY_UPLOAD_OFFSET}) to UTC, with daylight-saving
 * time taken from each row's {@code TimeZone}.
 *
 * <p>Runs once every bean exists and before the HTTP connectors open, so no upload can interleave
 * with it. The run is recorded in {@code dbo.SchemaMigrations} and skipped from then on, and the
 * original values are kept in {@code dbo.Images_Timestamp_Legacy}. Before touching anything the
 * legacy assumption is checked against the capture time in each file name (which the scripts
 * always wrote in local time): if the two disagree the migration stops and the application does
 * not start, rather than shifting rows that are not legacy. Set
 * {@code app.images.timestamp-migration.enabled=false} to skip the whole step.
 */
@Component
public class TimestampUtcMigration implements SmartInitializingSingleton {
    static final String MIGRATION_NAME = "images_timestamp_utc";
    static final String BACKUP_TABLE = "dbo.Images_Timestamp_Legacy";
    private static final int BATCH_SIZE = 1000;
    private static final long LEGACY_HOURS = CaptureTimeZones.LEGACY_UPLOAD_OFFSET.toHours();
    /** Upload delay tolerated between the file-name capture time and the row's timestamp. */
    private static final Duration NAME_TOLERANCE = Duration.ofMinutes(10);
    /** Share of named rows that must agree with the legacy rule for the conversion to run. */
    private static final double REQUIRED_AGREEMENT = 0.99;
    private static final Pattern NAME_TIME = Pattern.compile("_(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)$");

    private static final Logger log = LoggerFactory.getLogger(TimestampUtcMigration.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CaptureTimeZones timeZones;
    private final boolean enabled;

    public TimestampUtcMigration(JdbcTemplate jdbc,
                                 PlatformTransactionManager transactionManager,
                                 CaptureTimeZones timeZones,
                                 @Value("${app.images.timestamp-migration.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.timeZones = timeZones;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            log.info("Timestamp UTC migration is disabled");
            return;
        }
        jdbc.execute("IF OBJECT_ID('dbo.SchemaMigrations', 'U') IS NULL "
                + "CREATE TABLE dbo.SchemaMigrations ("
                + "Name nvarchar(100) NOT NULL PRIMARY KEY, AppliedAt datetime2 NOT NULL, Detail nvarchar(1000) NULL)");
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dbo.SchemaMigrations WHERE Name = ?", Integer.class, MIGRATION_NAME);
        if (applied != null && applied > 0) {
            log.debug("Timestamp UTC migration already applied");
            return;
        }
        log.info("Converting Images.Timestamp from legacy local + {} h to UTC", LEGACY_HOURS);
        String detail = transaction.execute(status -> convert());
        log.info("Timestamp UTC migration applied: {}", detail);
    }

    private record Conversion(long imgId, LocalDateTime utc) {
    }

    private String convert() {
        List<Conversion> conversions = new ArrayList<>();
        Map<String, Integer> perZone = new TreeMap<>();
        int[] counters = new int[3]; // rows without a timestamp, rows with a parsable name, names agreeing
        LocalDateTime[] range = new LocalDateTime[2];

        jdbc.query("SELECT ImgId, Timestamp, TimeZone, ImgPath FROM dbo.Images", (RowCallbackHandler) rs -> {
            long imgId = rs.getLong(1);
            LocalDateTime legacy = rs.getObject(2, LocalDateTime.class);
            if (legacy == null) {
                counters[0]++;
                return;
            }
            ZoneId zone = timeZones.resolve(rs.getString(3));
            LocalDateTime local = legacy.minus(CaptureTimeZones.LEGACY_UPLOAD_OFFSET);
            LocalDateTime named = captureTimeFromName(rs.getString(4));
            if (named != null) {
                counters[1]++;
                if (Duration.between(named, local).abs().compareTo(NAME_TOLERANCE) <= 0) {
                    counters[2]++;
                }
            }
            conversions.add(new Conversion(imgId, CaptureTimeZones.toUtc(local, zone)));
            perZone.merge(zone.getId(), 1, Integer::sum);
            range[0] = range[0] == null || legacy.isBefore(range[0]) ? legacy : range[0];
            range[1] = range[1] == null || legacy.isAfter(range[1]) ? legacy : range[1];
        });

        if (conversions.isEmpty()) {
            String detail = "no rows to convert";
            recordApplied(detail);
            return detail;
        }
        if (counters[1] > 0 && counters[2] < REQUIRED_AGREEMENT * counters[1]) {
            throw new IllegalStateException(String.format(
                    "Refusing to convert Images.Timestamp: only %d of %d file names agree with the legacy "
                            + "'local + %d h' rule, so the column does not look legacy. Check the data, or set "
                            + "app.images.timestamp-migration.enabled=false if it already holds UTC.",
                    counters[2], counters[1], LEGACY_HOURS));
        }

        jdbc.execute("IF OBJECT_ID('" + BACKUP_TABLE + "', 'U') IS NULL "
                + "SELECT ImgId, Timestamp, TimeZone INTO " + BACKUP_TABLE + " FROM dbo.Images");

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (Conversion conversion : conversions) {
            batch.add(new Object[] {conversion.utc(), conversion.imgId()});
            if (batch.size() == BATCH_SIZE) {
                jdbc.batchUpdate("UPDATE dbo.Images SET Timestamp = ? WHERE ImgId = ?", batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("UPDATE dbo.Images SET Timestamp = ? WHERE ImgId = ?", batch);
        }

        String detail = String.format(
                "%d rows converted from legacy local + %d h to UTC (zones %s; legacy range %s .. %s; "
                        + "%d of %d file names agreed; %d rows without a timestamp untouched); originals in %s",
                conversions.size(), LEGACY_HOURS, perZone, range[0], range[1],
                counters[2], counters[1], counters[0], BACKUP_TABLE);
        recordApplied(detail);
        return detail;
    }

    private void recordApplied(String detail) {
        jdbc.update("INSERT INTO dbo.SchemaMigrations (Name, AppliedAt, Detail) VALUES (?, ?, ?)",
                MIGRATION_NAME, LocalDateTime.now(ZoneOffset.UTC), detail);
    }

    /** The local capture time the camera scripts put at the end of every file name, if present. */
    static LocalDateTime captureTimeFromName(String imgPath) {
        if (imgPath == null) {
            return null;
        }
        Matcher m = NAME_TIME.matcher(imgPath.trim());
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
