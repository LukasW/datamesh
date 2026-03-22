package ch.yuno.partner.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Address identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record AddressId(String value) {

    public AddressId {
        Objects.requireNonNull(value, "AddressId must not be null");
    }

    public static AddressId generate() {
        return new AddressId(UUID.randomUUID().toString());
    }

    public static AddressId of(String value) {
        return new AddressId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

