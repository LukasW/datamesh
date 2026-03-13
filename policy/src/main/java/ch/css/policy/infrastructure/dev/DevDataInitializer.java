package ch.css.policy.infrastructure.dev;

import ch.css.policy.domain.model.Deckungstyp;
import ch.css.policy.domain.model.PartnerSicht;
import ch.css.policy.domain.model.ProduktSicht;
import ch.css.policy.domain.port.out.PartnerSichtRepository;
import ch.css.policy.domain.port.out.ProduktSichtRepository;
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
    private static final String PRODUKT_HAUSRAT  = "aaaaaaa1-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PRODUKT_GEBAUDE  = "aaaaaaa2-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PRODUKT_HAFTPFL  = "aaaaaaa3-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Inject
    PolicyApplicationService policyService;

    @Inject
    PartnerSichtRepository partnerSichtRepository;

    @Inject
    ProduktSichtRepository produktSichtRepository;

    void onStart(@Observes StartupEvent event) {
        if (!policyService.listAllPolicen().isEmpty()) {
            log.info("Dev data already present, skipping seed.");
            return;
        }
        log.info("Seeding dev test data...");
        seedReadModels();
        seedHausratMuster();
        seedGebaeudeAktiv();
        seedHaftpflichtEntwurf();
        seedGekuendigt();
        log.info("Dev data seeded: 4 Policen.");
    }

    // ── Read Model Seed ───────────────────────────────────────────────────────

    /**
     * Seed PartnerSicht and ProduktSicht read models.
     * Mirrors what would arrive via Kafka from the Partner and Product services.
     */
    private void seedReadModels() {
        partnerSichtRepository.upsert(new PartnerSicht(PARTNER_MUSTER,  "Max Muster"));
        partnerSichtRepository.upsert(new PartnerSicht(PARTNER_MUELLER, "Anna Müller"));
        partnerSichtRepository.upsert(new PartnerSicht(PARTNER_MEIER,   "Hans Meier"));

        produktSichtRepository.upsert(new ProduktSicht(PRODUKT_HAUSRAT, "Hausrat Basis",       "HAUSRAT",     new BigDecimal("100.00"), true));
        produktSichtRepository.upsert(new ProduktSicht(PRODUKT_GEBAUDE, "Gebäude Kompakt",     "GEBAEUDE",    new BigDecimal("200.00"), true));
        produktSichtRepository.upsert(new ProduktSicht(PRODUKT_HAFTPFL, "Haftpflicht Premium", "HAFTPFLICHT", new BigDecimal("80.00"),  true));
    }

    /** Hausrat police – AKTIV, mit Deckungen */
    private void seedHausratMuster() {
        String id = policyService.createPolicy(
                "POL-2024-0001", PARTNER_MUSTER, PRODUKT_HAUSRAT,
                LocalDate.of(2024, 1, 1), null,
                new BigDecimal("320.00"), new BigDecimal("200.00"));
        policyService.addDeckung(id, Deckungstyp.HAUSRAT, new BigDecimal("80000.00"));
        policyService.addDeckung(id, Deckungstyp.GLASBRUCH, new BigDecimal("5000.00"));
        policyService.addDeckung(id, Deckungstyp.DIEBSTAHL, new BigDecimal("10000.00"));
        policyService.aktivierePolicy(id);
    }

    /** Gebäude police – AKTIV, unbefristet */
    private void seedGebaeudeAktiv() {
        String id = policyService.createPolicy(
                "POL-2023-0042", PARTNER_MUELLER, PRODUKT_GEBAUDE,
                LocalDate.of(2023, 3, 1), null,
                new BigDecimal("1250.00"), new BigDecimal("500.00"));
        policyService.addDeckung(id, Deckungstyp.GEBAEUDE, new BigDecimal("750000.00"));
        policyService.addDeckung(id, Deckungstyp.ELEMENTAR, new BigDecimal("250000.00"));
        policyService.aktivierePolicy(id);
    }

    /** Haftpflicht – im Entwurf, noch nicht aktiviert */
    private void seedHaftpflichtEntwurf() {
        String id = policyService.createPolicy(
                "POL-2026-0007", PARTNER_MEIER, PRODUKT_HAFTPFL,
                LocalDate.of(2026, 4, 1), null,
                new BigDecimal("180.00"), new BigDecimal("0.00"));
        policyService.addDeckung(id, Deckungstyp.HAFTPFLICHT, new BigDecimal("5000000.00"));
    }

    /** Gekündigte Police */
    private void seedGekuendigt() {
        String id = policyService.createPolicy(
                "POL-2022-0099", PARTNER_MUSTER, PRODUKT_HAUSRAT,
                LocalDate.of(2022, 1, 1), LocalDate.of(2024, 12, 31),
                new BigDecimal("280.00"), new BigDecimal("150.00"));
        policyService.addDeckung(id, Deckungstyp.HAUSRAT, new BigDecimal("60000.00"));
        policyService.aktivierePolicy(id);
        policyService.kuendigePolicy(id);
    }
}

