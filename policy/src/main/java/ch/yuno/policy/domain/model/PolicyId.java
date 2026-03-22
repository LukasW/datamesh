package ch.yuno.policy.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Policy identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record PolicyId(String value) {

    public PolicyId {
        Objects.requireNonNull(value, "PolicyId must not be null");
    }

    public static PolicyId generate() {
        return new PolicyId(UUID.randomUUID().toString());
    }

    public static PolicyId of(String value) {
        return new PolicyId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
