package com.bagade.catalog.util;

import com.bagade.common.error.RequestValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CatalogValues {

    private static final Pattern INR_PRICE_PATTERN = Pattern.compile("^\\d{1,10}(\\.\\d{1,2})?$");

    private CatalogValues() {
    }

    public static String normalizeRequiredText(String raw) {
        return raw == null ? null : raw.trim();
    }

    public static String normalizeOptionalText(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static boolean isValidInrPriceString(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return !trimmed.isEmpty() && INR_PRICE_PATTERN.matcher(trimmed).matches();
    }

    public static BigDecimal parsePrice(String raw, String fieldName) {
        if (!isValidInrPriceString(raw)) {
            throw new RequestValidationException(
                    fieldName,
                    "Must be a valid non-negative INR decimal with at most 2 decimal places"
            );
        }
        return new BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static String formatPrice(BigDecimal price) {
        if (price == null) {
            return null;
        }
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String buildLikePattern(String query) {
        String normalized = normalizeOptionalText(query);
        if (normalized == null) {
            return null;
        }
        String escaped = normalized.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
