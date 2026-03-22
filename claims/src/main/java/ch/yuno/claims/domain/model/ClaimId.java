package ch.yuno.claims.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Claim identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record ClaimId(String value) {

    public ClaimId {
        Objects.requireNonNull(value, "ClaimId must not be null");
    }

    public static ClaimId generate() {
        return new ClaimId(UUID.randomUUID().toString());
    }

    public static ClaimId of(String value) {
        return new ClaimId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

