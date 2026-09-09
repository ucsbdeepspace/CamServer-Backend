package edu.camserver.app.service;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.NumberExpression;
import edu.camserver.app.model.Image;
import edu.camserver.app.model.ImageFilter;
import edu.camserver.app.model.QImage;
import edu.camserver.app.repository.ImageRepository;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ImageService {
    private static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * Sky periods by hour of the site's local day. {@code fromHour} is inclusive, {@code toHour}
     * exclusive, and a range whose end is not after its start wraps past midnight.
     */
    enum Period {
        DAWN(5, 7),
        DAY(7, 18),
        DUSK(18, 20),
        NIGHT(20, 5);

        private final int fromHour;
        private final int toHour;

        Period(int fromHour, int toHour) {
            this.fromHour = fromHour;
            this.toHour = toHour;
        }

        static Optional<Period> parse(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        /** Whether {@code localMinutes}, minutes since the site's local midnight, falls in this period. */
        Predicate contains(NumberExpression<Integer> localMinutes) {
            int from = fromHour * 60;
            int to = toHour * 60;
            return from < to
                    ? localMinutes.goe(from).and(localMinutes.lt(to))
                    : localMinutes.goe(from).or(localMinutes.lt(to));
        }
    }

    private final ImageRepository imageRepository;
    private final CaptureTimeZones timeZones;

    public ImageService(ImageRepository imageRepository, CaptureTimeZones timeZones) {
        this.imageRepository = imageRepository;
        this.timeZones = timeZones;
    }

    @Transactional
    public Image save(Image image) {
        return imageRepository.save(image);
    }

    @Modifying
    public Image setFeatured(long imgId, boolean featured) {
        Image image = imageRepository.findById(imgId).orElseThrow(() -> new NoResultException("Image not found"));
        image.setFeatured(featured);
        imageRepository.save(image);
        return image;
    }

    public Image findById(long imgId) {
        return imageRepository.findById(imgId).orElseThrow(() -> new NoResultException("Image not found"));
    }

    public List<Image> findAll(int pageSize, String lastUID, ImageFilter filter) {

        QImage image = QImage.image;
        BooleanBuilder builder = new BooleanBuilder();
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 100));

        if (hasText(lastUID)) {
            builder.and(image.imgId.lt(Long.parseLong(lastUID)));
        }

        if (filter.getFeatured() != null) {
            builder.and(image.featured.eq(filter.getFeatured()));
        }

        if (hasText(filter.getSiteName())) {
            builder.and(image.siteName.eq(filter.getSiteName()));
        }

        if (hasText(filter.getSearch())) {
            String search = filter.getSearch().trim();
            builder.and(
                    image.siteName.containsIgnoreCase(search)
                            .or(image.cameraId.containsIgnoreCase(search))
                            .or(image.imgPath.containsIgnoreCase(search))
                            .or(image.timeZone.containsIgnoreCase(search))
            );
        }

        Period period = Period.parse(filter.getPeriod()).orElse(null);
        if (period != null || filter.getStartDate() != null || filter.getEndDate() != null) {
            localTimePredicate(image, filter, period).ifPresent(builder::and);
        }

        Pageable pageable = PageRequest.of(
                0,
                normalizedPageSize,
                Sort.by("imgId").descending()
        );

        if (!builder.hasValue()) {
            return imageRepository.findAll(pageable).getContent();
        }

        return imageRepository.findAll(builder, pageable).getContent();
    }

    /**
     * Date bounds and sky periods are meant in each site's own local time, while the column holds
     * UTC. The predicate is therefore built per time zone found on the rows: (rows of zone A and
     * the bounds/period translated for zone A) or (rows of zone B and ...).
     */
    private Optional<Predicate> localTimePredicate(QImage image, ImageFilter filter, Period period) {
        Map<ZoneId, List<String>> zones = zonesOnRows();
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime earliest = period == null ? null : imageRepository.findEarliestTimestampUtc();

        BooleanBuilder anyZone = new BooleanBuilder();
        zones.forEach((zone, names) -> {
            BooleanBuilder inZone = new BooleanBuilder(rowsInZone(image, names));
            if (filter.getStartDate() != null) {
                inZone.and(image.timestampUtc.goe(CaptureTimeZones.toUtc(filter.getStartDate(), zone)));
            }
            if (filter.getEndDate() != null) {
                inZone.and(image.timestampUtc.loe(CaptureTimeZones.toUtc(filter.getEndDate(), zone)));
            }
            if (period != null) {
                inZone.and(periodPredicate(image, zone, period, earliest));
            }
            anyZone.or(inZone);
        });
        return Optional.of(anyZone);
    }

    /** The distinct {@code TimeZone} values on the rows, grouped by the zone each resolves to. */
    private Map<ZoneId, List<String>> zonesOnRows() {
        Map<ZoneId, List<String>> zones = new LinkedHashMap<>();
        for (String name : imageRepository.findDistinctTimeZones()) {
            zones.computeIfAbsent(timeZones.resolve(name), zone -> new ArrayList<>()).add(name);
        }
        return zones;
    }

    private static Predicate rowsInZone(QImage image, List<String> names) {
        BooleanBuilder match = new BooleanBuilder();
        List<String> known = names.stream().filter(Objects::nonNull).toList();
        if (!known.isEmpty()) {
            match.or(image.timeZone.in(known));
        }
        if (names.contains(null)) {
            match.or(image.timeZone.isNull());
        }
        return match;
    }

    /**
     * Local time of day is the UTC time shifted by the zone's offset, and the offset changes at
     * daylight-saving transitions. The rows are therefore split into ranges of constant offset,
     * from the earliest capture to a year ahead, and each range gets its own arithmetic.
     */
    static Predicate periodPredicate(QImage image, ZoneId zone, Period period, LocalDateTime earliestUtc) {
        ZoneRules rules = zone.getRules();
        Instant start = (earliestUtc == null ? LocalDateTime.now(ZoneOffset.UTC) : earliestUtc).toInstant(ZoneOffset.UTC);
        Instant end = Instant.now().plus(Duration.ofDays(366));

        BooleanBuilder anyRange = new BooleanBuilder();
        while (start.isBefore(end)) {
            ZoneOffsetTransition transition = rules.nextTransition(start);
            Instant rangeEnd = transition == null || !transition.getInstant().isBefore(end)
                    ? end
                    : transition.getInstant();
            int shiftMinutes = Math.floorMod(rules.getOffset(start).getTotalSeconds() / 60, MINUTES_PER_DAY);
            NumberExpression<Integer> localMinutes = image.timestampUtc.hour().multiply(60)
                    .add(image.timestampUtc.minute())
                    .add(shiftMinutes)
                    .mod(MINUTES_PER_DAY);
            anyRange.or(image.timestampUtc.goe(utc(start))
                    .and(image.timestampUtc.lt(utc(rangeEnd)))
                    .and(period.contains(localMinutes)));
            start = rangeEnd;
        }
        return anyRange;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
