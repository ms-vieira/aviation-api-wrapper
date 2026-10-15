package com.msvieira.aviation.domain;

import com.msvieira.aviation.domain.exception.InvalidIcaoCodeException;

import java.util.Locale;
import java.util.regex.Pattern;

public record IcaoCode(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z]{4}$");

    public IcaoCode {
        if (value == null || value.isBlank()) {
            throw new InvalidIcaoCodeException(value);
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!PATTERN.matcher(value).matches()) {
            throw new InvalidIcaoCodeException(value);
        }
    }

    public static IcaoCode of(final String raw) {
        return new IcaoCode(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
