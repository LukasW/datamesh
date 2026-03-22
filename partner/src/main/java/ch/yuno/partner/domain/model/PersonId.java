package ch.yuno.partner.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Person identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record PersonId(String value) {

    public PersonId {
        Objects.requireNonNull(value, "PersonId must not be null");
    }

    public static PersonId generate() {
        return new PersonId(UUID.randomUUID().toString());
    }

    public static PersonId of(String value) {
        return new PersonId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
