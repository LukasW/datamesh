package ch.yuno.product.domain;

import ch.yuno.product.domain.model.PremiumCalculation;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.model.RiskProfile;
import ch.yuno.product.domain.service.PremiumCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PremiumCalculationService (pure domain logic, no framework).
 */
class PremiumCalculationServiceTest {

    private PremiumCalculationService service;
    private Product household;

    @BeforeEach
    void setUp() {
        service = new PremiumCalculationService();
        household = new Product("Hausrat Basis", "Basic household insurance",
                ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("1000.00"));
    }

    @Test
    void calculate_standardProfile_returnsBasePremium() {
        RiskProfile profile = new RiskProfile(35, "6000", "LU", List.of("HOUSEHOLD_CONTENTS"));

        PremiumCalculation result = service.calculate(household, profile);

        assertNotNull(result.calculationId());
        assertEquals(new BigDecimal("1000.00"), result.basePremium());
        assertEquals(new BigDecimal("0.00"), result.riskSurcharge());
        assertEquals(new BigDecimal("0.00"), result.coverageSurcharge());
        assertEquals(new BigDecimal("0.00"), result.discount());
        assertEquals(new BigDecimal("1000.00"), result.totalPremium());
        assertEquals("CHF", result.currency());
    }

    @Test
    void calculate_youngPolicyholder_appliesYouthSurcharge() {
        RiskProfile profile = new RiskProfile(20, "6000", "LU", List.of("HOUSEHOLD_CONTENTS"));

        PremiumCalculation result = service.calculate(household, profile);

        // +10% surcharge for age < 25: 1000 * 0.10 = 100
        assertEquals(new BigDecimal("100.00"), result.riskSurcharge());
        assertEquals(new BigDecimal("1100.00"), result.totalPremium());
    }

    @Test
    void calculate_seniorPolicyholder_appliesSeniorSurcharge() {
        RiskProfile profile = new RiskProfile(75, "6000", "LU", List.of("HOUSEHOLD_CONTENTS"));

        PremiumCalculation result = service.calculate(household, profile);

        // +20% surcharge for age > 70: 1000 * 0.20 = 200
        assertEquals(new BigDecimal("200.00"), result.riskSurcharge());
        assertEquals(new BigDecimal("1200.00"), result.totalPremium());
    }

    @Test
    void calculate_urbanArea_appliesUrbanSurcharge() {
        RiskProfile profile = new RiskProfile(35, "8001", "ZH", List.of("HOUSEHOLD_CONTENTS"));

        PremiumCalculation result = service.calculate(household, profile);

        // +5% surcharge for urban area (Zurich): 1000 * 0.05 = 50
        assertEquals(new BigDecimal("50.00"), result.riskSurcharge());
        assertEquals(new BigDecimal("1050.00"), result.totalPremium());
    }

    @Test
    void calculate_youngUrbanPolicyholder_combinesSurcharges() {
        RiskProfile profile = new RiskProfile(22, "8001", "ZH", List.of("HOUSEHOLD_CONTENTS"));

        PremiumCalculation result = service.calculate(household, profile);

        // +10% age + 5% urban = 100 + 50 = 150
        assertEquals(new BigDecimal("150.00"), result.riskSurcharge());
        assertEquals(new BigDecimal("1150.00"), result.totalPremium());
    }

    @Test
    void calculate_multipleCoverages_appliesCoverageSurcharge() {
        RiskProfile profile = new RiskProfile(35, "6000", "LU",
                List.of("HOUSEHOLD_CONTENTS", "THEFT"));

        PremiumCalculation result = service.calculate(household, profile);

        // +3% per additional coverage: 1 additional * 1000 * 0.03 = 30
        assertEquals(new BigDecimal("30.00"), result.coverageSurcharge());
        assertEquals(new BigDecimal("1030.00"), result.totalPremium());
    }

    @Test
    void calculate_threeCoverages_appliesBundleDiscount() {
        RiskProfile profile = new RiskProfile(35, "6000", "LU",
                List.of("HOUSEHOLD_CONTENTS", "THEFT", "GLASS_BREAKAGE"));

        PremiumCalculation result = service.calculate(household, profile);

        // Coverage surcharge: 2 additional * 1000 * 0.03 = 60
        assertEquals(new BigDecimal("60.00"), result.coverageSurcharge());
        // Bundle discount: 1000 * 0.05 = 50
        assertEquals(new BigDecimal("50.00"), result.discount());
        // Total: 1000 + 0 + 60 - 50 = 1010
        assertEquals(new BigDecimal("1010.00"), result.totalPremium());
    }

    @Test
    void calculate_zeroPremiumProduct_returnsZero() {
        Product free = new Product("Gratis", "Free bundle", ProductLine.TRAVEL, BigDecimal.ZERO);
        RiskProfile profile = new RiskProfile(35, "6000", "LU", List.of());

        PremiumCalculation result = service.calculate(free, profile);

        assertEquals(new BigDecimal("0.00"), result.totalPremium());
    }

    @Test
    void riskProfile_invalidAge_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskProfile(-1, "8001", "ZH", List.of()));
    }

    @Test
    void riskProfile_nullPostalCode_throwsException() {
        assertThrows(NullPointerException.class, () ->
                new RiskProfile(30, null, "ZH", List.of()));
    }

    @Test
    void riskProfile_blankPostalCode_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                new RiskProfile(30, "  ", "ZH", List.of()));
    }
}


