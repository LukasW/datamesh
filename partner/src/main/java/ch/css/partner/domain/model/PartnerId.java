package ch.css.partner.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Partner identifiers.
 * Used for cross-domain references (e.g. from Policy to Partner).
 * Prevents accidental mixing of different ID types at compile time.
 */
public record PartnerId(String value) {

    public PartnerId {
        Objects.requireNonNull(value, "PartnerId must not be null");
    }

    public static PartnerId generate() {
        return new PartnerId(UUID.randomUUID().toString());
    }

    public static PartnerId of(String value) {
        return new PartnerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
