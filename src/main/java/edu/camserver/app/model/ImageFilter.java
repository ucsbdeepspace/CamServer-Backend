package edu.camserver.app.model;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Gallery query filters. {@code startDate} and {@code endDate} are wall-clock bounds in each
 * site's own time zone (a day picked on the site means that site's day), not UTC; so is the hour
 * range behind {@code period}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class ImageFilter {
    private Boolean featured;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String siteName;
    private String search;
    private String period;
}
