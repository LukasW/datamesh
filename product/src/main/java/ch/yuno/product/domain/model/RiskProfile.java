package ch.yuno.product.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Value Object representing the risk profile of a policyholder.
 * Used as input for premium calculation.
 * <p>
 * Pure Java – no framework dependencies.
 */
public record RiskProfile(
        int age,
        String postalCode,
        String canton,
        List<String> coverageTypes
) {
    public RiskProfile {
        if (age < 0 || age > 150) {
            throw new IllegalArgumentException("Invalid age: " + age);
        }
        Objects.requireNonNull(postalCode, "postalCode must not be null");
        if (postalCode.isBlank()) {
            throw new IllegalArgumentException("postalCode must not be blank");
        }
        if (coverageTypes == null) {
            coverageTypes = List.of();
        } else {
            coverageTypes = List.copyOf(coverageTypes);
        }
    }
}

