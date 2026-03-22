package ch.yuno.policy.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Coverage identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record CoverageId(String value) {

    public CoverageId {
        Objects.requireNonNull(value, "CoverageId must not be null");
    }

    public static CoverageId generate() {
        return new CoverageId(UUID.randomUUID().toString());
    }

    public static CoverageId of(String value) {
        return new CoverageId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

