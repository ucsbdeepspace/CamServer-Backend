package edu.camserver.app.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;


@Entity
@NoArgsConstructor
@Table(name = "Images", schema = "dbo")
@Getter
@Setter
@ToString
public class Image {

    @Id
    @Column(unique = true, name = "ImgId")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long imgId;

    // CamId is a fixed-width char column, so values come back space-padded.
    @Column(name = "CamId")
    @Getter(AccessLevel.NONE)
    private String cameraId;

    // Derived from the Cameras table; there is no site name stored on the image row itself.
    @Formula("(SELECT c.SiteName FROM Cameras c WHERE c.CamId = CamId)")
    @Setter(AccessLevel.NONE)
    private String siteName;

    public String getCameraId() {
        return cameraId == null ? null : cameraId.trim();
    }

    /**
     * Capture time as UTC wall-clock time. The {@code Timestamp} column is a zone-less SQL Server
     * {@code datetime}; every value in it is UTC (rows written under the old upload convention were
     * converted once by {@link edu.camserver.app.service.TimestampUtcMigration}). Queries address
     * the column as {@code QImage.image.timestampUtc}; everything else uses the
     * {@link #getTimestamp() Instant} view, which is also what the JSON {@code timestamp} field
     * carries.
     */
    @Column(name = "Timestamp")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private LocalDateTime timestampUtc;

    @Column(name = "BitDepth")
    private int bit;

    @Column(name = "Gain")
    private int gain;

    @Column(name = "ExpTime")
    private int exposure;

    @Column(name = "ImgPath")
    private String imgPath;

    @Column(name = "Temperature")
    private float temperature;

    @Column(name = "Humidity")
    private float humidity;

    // IANA zone name of the site (e.g. America/Los_Angeles): the zone the capture time is shown in.
    @Column(name = "TimeZone")
    private String timeZone;

    // Day/night flag the camera scripts send as isDay=1/0; NULL where a client never sent it.
    @Column(name = "IsDayTime")
    private Boolean isDayTime;

    // Legacy rows have NULL here; treat that as "not featured" instead of failing to load.
    @Column(name = "Feat")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean featured;

    public boolean isFeatured() {
        return Boolean.TRUE.equals(featured);
    }

    public void setFeatured(boolean featured) {
        this.featured = featured;
    }

    /** The capture instant, serialised as ISO-8601 UTC such as {@code 2026-09-05T05:35:12.123Z}. */
    @JsonProperty("timestamp")
    public Instant getTimestamp() {
        return timestampUtc == null ? null : timestampUtc.toInstant(ZoneOffset.UTC);
    }

    public void setTimestamp(Instant timestamp) {
        this.timestampUtc = timestamp == null ? null : LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC);
    }

    public Image(String cameraId, String siteName, Instant timestamp, int bit, int gain, int exposure, String imgPath, float temperature, float humidity, String timeZone, boolean featured) {
        this.cameraId = cameraId;
        this.siteName = siteName;
        setTimestamp(timestamp);
        this.bit = bit;
        this.gain = gain;
        this.exposure = exposure;
        this.imgPath = imgPath;
        this.temperature = temperature;
        this.humidity = humidity;
        this.timeZone = timeZone;
        this.featured = featured;
    }
}
