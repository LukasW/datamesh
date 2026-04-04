package ch.yuno.product.infrastructure.dev;

import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.application.ProductCommandService;
import ch.yuno.product.application.ProductQueryService;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Seed realistic product test data in dev mode.
 * Only active when the "dev" build profile is active (i.e. quarkus:dev).
 * Idempotent: skips seeding if products already exist.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevDataInitializer {

    private static final Logger log = Logger.getLogger(DevDataInitializer.class);

    @Inject
    ProductCommandService productCommandService;

    @Inject
    ProductQueryService productQueryService;

    void onStart(@Observes StartupEvent event) {
        if (!productQueryService.listAllProducts().isEmpty()) {
            log.info("Dev data already present, skipping seed.");
            return;
        }
        log.info("Seeding product dev test data...");

        productCommandService.defineProduct(
                "Yuno Hausrat Basis",
                "Grundschutz für Hausrat gegen Feuer, Wasser und Diebstahl.",
                ProductLine.HOUSEHOLD_CONTENTS,
                new BigDecimal("180.00"));

        productCommandService.defineProduct(
                "Yuno Hausrat Komfort",
                "Erweiterter Hausratschutz inkl. Glasbruch und grobe Fahrlässigkeit.",
                ProductLine.HOUSEHOLD_CONTENTS,
                new BigDecimal("320.00"));

        productCommandService.defineProduct(
                "Yuno Privathaftpflicht",
                "Schutz bei Schäden, die Sie als Privatperson verursachen.",
                ProductLine.LIABILITY,
                new BigDecimal("95.00"));

        productCommandService.defineProduct(
                "Yuno Motorfahrzeug Classic",
                "Vollkasko- und Haftpflichtschutz für Personenwagen.",
                ProductLine.MOTOR_VEHICLE,
                new BigDecimal("650.00"));

        productCommandService.defineProduct(
                "Yuno Reiseversicherung",
                "Annullierungskosten, Assistance und Gepäckschutz weltweit.",
                ProductLine.TRAVEL,
                new BigDecimal("120.00"));

        // Demonstrate deprecated status
        ProductId oldId = productCommandService.defineProduct(
                "Yuno Motorfahrzeug Legacy",
                "Älteres Motorfahrzeugprodukt (nicht mehr erhältlich).",
                ProductLine.MOTOR_VEHICLE,
                new BigDecimal("500.00"));
        productCommandService.deprecateProduct(oldId);

        log.info("Dev data seeded: 6 products (1 deprecated).");
    }
}
