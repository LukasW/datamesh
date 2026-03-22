package ch.yuno.product.domain.service;
import ch.yuno.product.domain.model.PremiumCalculation;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.RiskProfile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
/**
 * Domain Service for premium calculation.
 * Pure business logic - no framework dependencies.
 * Computes the risk-adjusted premium based on the product base premium
 * and the policyholder risk profile (age, postal code, coverages).
 */
public class PremiumCalculationService {
    private static final BigDecimal YOUNG_SURCHARGE = new BigDecimal("0.10");
    private static final BigDecimal SENIOR_SURCHARGE = new BigDecimal("0.20");
    private static final BigDecimal URBAN_SURCHARGE = new BigDecimal("0.05");
    private static final BigDecimal COVERAGE_SURCHARGE_RATE = new BigDecimal("0.03");
    private static final BigDecimal BUNDLE_DISCOUNT = new BigDecimal("0.05");
    private static final String CURRENCY = "CHF";
    private static final List<String> URBAN_POSTAL_PREFIXES = List.of(
            "80", "81", "30", "31", "40", "12", "10"
    );
    public PremiumCalculation calculate(Product product, RiskProfile riskProfile) {
        BigDecimal base = product.getBasePremium();
        BigDecimal riskSurcharge = calculateRiskSurcharge(base, riskProfile);
        BigDecimal coverageSurcharge = calculateCoverageSurcharge(base, riskProfile.coverageTypes());
        BigDecimal discount = calculateDiscount(base, riskProfile);
        BigDecimal total = base.add(riskSurcharge)
                .add(coverageSurcharge)
                .subtract(discount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new PremiumCalculation(
                UUID.randomUUID().toString(),
                base,
                riskSurcharge.setScale(2, RoundingMode.HALF_UP),
                coverageSurcharge.setScale(2, RoundingMode.HALF_UP),
                discount.setScale(2, RoundingMode.HALF_UP),
                total,
                CURRENCY
        );
    }
    private BigDecimal calculateRiskSurcharge(BigDecimal base, RiskProfile profile) {
        BigDecimal surcharge = BigDecimal.ZERO;
        if (profile.age() < 25) {
            surcharge = surcharge.add(base.multiply(YOUNG_SURCHARGE));
        } else if (profile.age() > 70) {
            surcharge = surcharge.add(base.multiply(SENIOR_SURCHARGE));
        }
        if (isUrbanArea(profile.postalCode())) {
            surcharge = surcharge.add(base.multiply(URBAN_SURCHARGE));
        }
        return surcharge;
    }
    private BigDecimal calculateCoverageSurcharge(BigDecimal base, List<String> coverageTypes) {
        if (coverageTypes.size() <= 1) {
            return BigDecimal.ZERO;
        }
        int additional = coverageTypes.size() - 1;
        return base.multiply(COVERAGE_SURCHARGE_RATE).multiply(BigDecimal.valueOf(additional));
    }
    private BigDecimal calculateDiscount(BigDecimal base, RiskProfile profile) {
        if (profile.coverageTypes().size() >= 3) {
            return base.multiply(BUNDLE_DISCOUNT);
        }
        return BigDecimal.ZERO;
    }
    private boolean isUrbanArea(String postalCode) {
        if (postalCode == null || postalCode.length() < 2) {
            return false;
        }
        return URBAN_POSTAL_PREFIXES.contains(postalCode.substring(0, 2));
    }
}
