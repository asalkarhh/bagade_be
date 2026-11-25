package com.bagade.common.util;

import com.bagade.catalog.util.CatalogValues;
import com.bagade.common.error.FieldErrorItem;
import com.bagade.common.error.RequestValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public final class ReportingDateHelper {

    private ReportingDateHelper() {
    }

    public static void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException(
                    "Validation failed",
                    List.of(
                            new FieldErrorItem("from", "'from' date must not be after 'to' date"),
                            new FieldErrorItem("to", "'to' date must not be before 'from' date")
                    )
            );
        }
    }

    public record PriceRange(BigDecimal minPrice, BigDecimal maxPrice) {}

    public static PriceRange parseAndValidatePriceRange(String rawMin, String rawMax) {
        BigDecimal min = rawMin != null ? CatalogValues.parsePrice(rawMin, "minPrice") : null;
        BigDecimal max = rawMax != null ? CatalogValues.parsePrice(rawMax, "maxPrice") : null;
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new RequestValidationException(
                    "Validation failed",
                    List.of(
                            new FieldErrorItem("minPrice", "minPrice must not be greater than maxPrice"),
                            new FieldErrorItem("maxPrice", "maxPrice must not be less than minPrice")
                    )
            );
        }
        return new PriceRange(min, max);
    }

    public static Instant startInclusive(LocalDate from, ZoneId shopZone) {
        return from.atStartOfDay(shopZone).toInstant();
    }

    public static Instant endExclusive(LocalDate to, ZoneId shopZone) {
        return to.plusDays(1).atStartOfDay(shopZone).toInstant();
    }
}
