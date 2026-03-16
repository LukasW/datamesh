package ch.css.policy.infrastructure.dev;

import ch.css.policy.domain.model.CoverageType;
import ch.css.policy.domain.model.PartnerView;
import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.PartnerViewRepository;
import ch.css.policy.domain.port.out.ProductViewRepository;
import ch.css.policy.domain.service.PolicyApplicationService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Seed realistic test data in dev mode.
 * Only active when the "dev" build profile is active (i.e. `quarkus:dev`).
 * Skips insertion when policies already exist (idempotent across hot reloads).
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevDataInitializer {

    private static final Logger log = Logger.getLogger(DevDataInitializer.class);

    // Fixed dev UUIDs – correspond to partner/product dev data
    private static final String PARTNER_MUSTER   = "11111111-1111-1111-1111-111111111111";
    private static final String PARTNER_MUELLER  = "22222222-2222-2222-2222-222222222222";
    private static final String PARTNER_MEIER    = "33333333-3333-3333-3333-333333333333";
    private static final String PRODUCT_HOUSEHOLD_CONTENTS_ID = "aaaaaaa1-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PRODUCT_BUILDING_ID           = "aaaaaaa2-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PRODUCT_LIABILITY_ID          = "aaaaaaa3-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Inject
    PolicyApplicationService policyService;

    @Inject
    PartnerViewRepository partnerViewRepository;

    @Inject
    ProductViewRepository productViewRepository;

    void onStart(@Observes StartupEvent event) {
        if (!policyService.listAllPolicies().isEmpty()) {
            log.info("Dev data already present, skipping seed.");
            return;
        }
        log.info("Seeding dev test data...");
        seedReadModels();
        seedHouseholdContentsSample();
        seedBuildingActive();
        seedLiabilityDraft();
        seedCancelled();
        log.info("Dev data seeded: 4 policies.");
    }

    // ── Read Model Seed ───────────────────────────────────────────────────────

    /**
     * Seed PartnerView and ProductView read models.
     * Mirrors what would arrive via Kafka from the Partner and Product services.
     */
    private void seedReadModels() {
        partnerViewRepository.upsert(new PartnerView(PARTNER_MUSTER,  "Max Muster"));
        partnerViewRepository.upsert(new PartnerView(PARTNER_MUELLER, "Anna Müller"));
        partnerViewRepository.upsert(new PartnerView(PARTNER_MEIER,   "Hans Meier"));

        productViewRepository.upsert(new ProductView(PRODUCT_HOUSEHOLD_CONTENTS_ID, "Hausrat Basis",       "HOUSEHOLD_CONTENTS", new BigDecimal("100.00"), true));
        productViewRepository.upsert(new ProductView(PRODUCT_BUILDING_ID, "Gebäude Kompakt",     "BUILDING",           new BigDecimal("200.00"), true));
        productViewRepository.upsert(new ProductView(PRODUCT_LIABILITY_ID, "Haftpflicht Premium", "LIABILITY",          new BigDecimal("80.00"),  true));
    }

    /** Household contents policy – ACTIVE, with coverages */
    private void seedHouseholdContentsSample() {
        String id = policyService.createPolicy(
                PARTNER_MUSTER, PRODUCT_HOUSEHOLD_CONTENTS_ID,
                LocalDate.of(2024, 1, 1), null,
                new BigDecimal("320.00"), new BigDecimal("200.00"));
        policyService.addCoverage(id, CoverageType.HOUSEHOLD_CONTENTS, new BigDecimal("80000.00"));
        policyService.addCoverage(id, CoverageType.GLASS_BREAKAGE, new BigDecimal("5000.00"));
        policyService.addCoverage(id, CoverageType.THEFT, new BigDecimal("10000.00"));
        policyService.activatePolicy(id);
    }

    /** Building policy – ACTIVE, open-ended */
    private void seedBuildingActive() {
        String id = policyService.createPolicy(
                PARTNER_MUELLER, PRODUCT_BUILDING_ID,
                LocalDate.of(2023, 3, 1), null,
                new BigDecimal("1250.00"), new BigDecimal("500.00"));
        policyService.addCoverage(id, CoverageType.BUILDING, new BigDecimal("750000.00"));
        policyService.addCoverage(id, CoverageType.NATURAL_HAZARD, new BigDecimal("250000.00"));
        policyService.activatePolicy(id);
    }

    /** Liability – DRAFT, not yet activated */
    private void seedLiabilityDraft() {
        String id = policyService.createPolicy(
                PARTNER_MEIER, PRODUCT_LIABILITY_ID,
                LocalDate.of(2026, 4, 1), null,
                new BigDecimal("180.00"), new BigDecimal("0.00"));
        policyService.addCoverage(id, CoverageType.LIABILITY, new BigDecimal("5000000.00"));
    }

    /** Cancelled policy */
    private void seedCancelled() {
        String id = policyService.createPolicy(
                PARTNER_MUSTER, PRODUCT_HOUSEHOLD_CONTENTS_ID,
                LocalDate.of(2022, 1, 1), LocalDate.of(2024, 12, 31),
                new BigDecimal("280.00"), new BigDecimal("150.00"));
        policyService.addCoverage(id, CoverageType.HOUSEHOLD_CONTENTS, new BigDecimal("60000.00"));
        policyService.activatePolicy(id);
        policyService.cancelPolicy(id);
    }
}
