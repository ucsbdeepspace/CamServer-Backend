package edu.camserver.app.service;

import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.HQLTemplates;
import com.querydsl.jpa.JPQLSerializer;
import edu.camserver.app.model.QImage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageServiceTest {

    @Test
    void periodNamesAreCaseInsensitiveAndUnknownOnesMeanNoFilter() {
        assertEquals(Optional.of(ImageService.Period.NIGHT), ImageService.Period.parse(" Night "));
        assertEquals(Optional.of(ImageService.Period.DAWN), ImageService.Period.parse("dawn"));
        assertEquals(Optional.empty(), ImageService.Period.parse("all"));
        assertEquals(Optional.empty(), ImageService.Period.parse(""));
        assertEquals(Optional.empty(), ImageService.Period.parse(null));
    }

    @Test
    void periodPredicateShiftsUtcByTheOffsetInForceAndSplitsAtDaylightSavingTransitions() {
        Predicate predicate = ImageService.periodPredicate(QImage.image, ZoneId.of("America/Los_Angeles"),
                ImageService.Period.NIGHT, LocalDateTime.parse("2025-05-22T05:54:22"));

        JPQLSerializer serializer = new JPQLSerializer(HQLTemplates.DEFAULT);
        serializer.handle(predicate);
        String jpql = serializer.toString();
        List<Object> constants = serializer.getConstants();

        assertTrue(jpql.contains("mod(") && jpql.contains("hour(") && jpql.contains("minute("), jpql);
        assertTrue(constants.contains(17 * 60), "UTC-7 (PDT) shifts by 17 h: " + constants);
        assertTrue(constants.contains(16 * 60), "UTC-8 (PST) shifts by 16 h: " + constants);
        assertTrue(constants.contains(LocalDateTime.parse("2025-11-02T09:00")),
                "range boundary at the 2025 fall-back transition (09:00 UTC): " + constants);
        assertTrue(constants.contains(LocalDateTime.parse("2026-03-08T10:00")),
                "range boundary at the 2026 spring-forward transition (10:00 UTC): " + constants);
        // Night wraps midnight: minutes >= 20:00 or < 05:00.
        assertTrue(constants.contains(20 * 60) && constants.contains(5 * 60), constants.toString());
    }
}
