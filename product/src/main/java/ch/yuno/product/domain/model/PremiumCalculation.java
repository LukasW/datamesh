package ch.yuno.product.domain.model;
import java.math.BigDecimal;
import java.util.Objects;
/**
 * Value Object representing the result of a premium calculation.
 * Pure Java - no framework dependencies.
 */
public record PremiumCalculation(
        String calculationId,
        BigDecimal basePremium,
        BigDecimal riskSurcharge,
        BigDecimal coverageSurcharge,
        BigDecimal discount,
        BigDecimal totalPremium,
        String currency
) {
    public PremiumCalculation {
        Objects.requireNonNull(calculationId, "calculationId must not be null");
        Objects.requireNonNull(basePremium, "basePremium must not be null");
        Objects.requireNonNull(riskSurcharge, "riskSurcharge must not be null");
        Objects.requireNonNull(coverageSurcharge, "coverageSurcharge must not be null");
        Objects.requireNonNull(discount, "discount must not be null");
        Objects.requireNonNull(totalPremium, "totalPremium must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
