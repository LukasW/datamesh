package ch.yuno.product.integration;

import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.service.ProductCommandService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Product - Outbox Roundtrip Integration Test")
class ProductOutboxRoundtripIT {

    @Inject
    ProductCommandService service;

    @Inject
    EntityManager em;

    @Test
    @DisplayName("Defining a product writes outbox entries to DB")
    void defineProduct_writesOutboxEntries() {
        ProductId productId = service.defineProduct(
                "Household Basic", "Basic household contents insurance",
                ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("120.00"));

        assertNotNull(productId);

        // Check outbox entries were written (ProductDefined + ProductState = 2 entries)
        var outboxEntries = em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", productId.value())
            .getSingleResult();
        assertTrue(((Number) outboxEntries).longValue() >= 2,
            "At least two outbox entries (ProductDefined + ProductState) should exist for the new product");
    }

    @Test
    @DisplayName("Deprecating a product writes additional outbox entries")
    void deprecateProduct_writesOutboxEntries() {
        ProductId productId = service.defineProduct(
                "To Deprecate", "Product to be deprecated",
                ProductLine.LIABILITY, new BigDecimal("200.00"));

        long countBefore = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", productId.value())
            .getSingleResult()).longValue();

        service.deprecateProduct(productId);

        long countAfter = ((Number) em.createNativeQuery(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = :id")
            .setParameter("id", productId.value())
            .getSingleResult()).longValue();

        assertTrue(countAfter > countBefore,
            "Deprecating a product should produce additional outbox entries");
    }
}
