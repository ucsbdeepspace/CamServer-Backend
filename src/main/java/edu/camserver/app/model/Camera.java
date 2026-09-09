package edu.camserver.app.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "Cameras", schema = "dbo")
@Getter
@Setter
public class Camera {

    @Id
    @Column(unique = true, nullable = false, name = "UID")
    private long UID;

    // CamId is a fixed-width char column, so values come back space-padded.
    @Column(name = "CamId")
    @Getter(AccessLevel.NONE)
    private String cameraId;

    @Column(name = "SiteName")
    private String siteName;

    @Column(name = "TimeZone")
    private String timeZone;

    // Will change from GeoLoc to longitude and latitude for better readability
    @Column(name = "Longitude")
    private Double longitude;

    @Column(name = "Lat")
    private Double latitude;

    public String getCameraId() {
        return cameraId == null ? null : cameraId.trim();
    }
}
