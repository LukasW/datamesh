package ch.yuno.policy.domain.model;
import java.math.BigDecimal;
import java.util.Objects;
/**
 * Value Object representing the result of a premium calculation received from the Product Service.
 * Pure Java - no framework dependencies.
 */
public record PremiumCalculationResult(
        String calculationId,
        BigDecimal basePremium,
        BigDecimal riskSurcharge,
        BigDecimal coverageSurcharge,
        BigDecimal discount,
        BigDecimal totalPremium,
        String currency
) {
    public PremiumCalculationResult {
        Objects.requireNonNull(calculationId);
        Objects.requireNonNull(totalPremium);
        Objects.requireNonNull(currency);
    }
}
