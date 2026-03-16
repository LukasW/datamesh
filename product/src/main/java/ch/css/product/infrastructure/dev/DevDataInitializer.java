package ch.css.product.infrastructure.dev;

import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.service.ProductApplicationService;
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
    ProductApplicationService productService;

    void onStart(@Observes StartupEvent event) {
        if (!productService.listAllProducts().isEmpty()) {
            log.info("Dev data already present, skipping seed.");
            return;
        }
        log.info("Seeding product dev test data...");

        productService.defineProduct(
                "CSS Hausrat Basis",
                "Grundschutz für Hausrat gegen Feuer, Wasser und Diebstahl.",
                ProductLine.HOUSEHOLD_CONTENTS,
                new BigDecimal("180.00"));

        productService.defineProduct(
                "CSS Hausrat Komfort",
                "Erweiterter Hausratschutz inkl. Glasbruch und grobe Fahrlässigkeit.",
                ProductLine.HOUSEHOLD_CONTENTS,
                new BigDecimal("320.00"));

        productService.defineProduct(
                "CSS Privathaftpflicht",
                "Schutz bei Schäden, die Sie als Privatperson verursachen.",
                ProductLine.LIABILITY,
                new BigDecimal("95.00"));

        String motorId = productService.defineProduct(
                "CSS Motorfahrzeug Classic",
                "Vollkasko- und Haftpflichtschutz für Personenwagen.",
                ProductLine.MOTOR_VEHICLE,
                new BigDecimal("650.00"));

        productService.defineProduct(
                "CSS Reiseversicherung",
                "Annullierungskosten, Assistance und Gepäckschutz weltweit.",
                ProductLine.TRAVEL,
                new BigDecimal("120.00"));

        // Demonstrate deprecated status
        String oldId = productService.defineProduct(
                "CSS Motorfahrzeug Legacy",
                "Älteres Motorfahrzeugprodukt (nicht mehr erhältlich).",
                ProductLine.MOTOR_VEHICLE,
                new BigDecimal("500.00"));
        productService.deprecateProduct(oldId);

        log.info("Dev data seeded: 6 products (1 deprecated).");
    }
}
