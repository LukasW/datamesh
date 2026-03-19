package ch.css.policy.integration;

import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.ProductViewRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@DisplayName("Policy - ProductView Materialization Integration Test")
class ProductViewMaterializationIT {

    @Inject
    ProductViewRepository productViewRepository;

    @Test
    @DisplayName("Upsert and find a ProductView in the read model")
    void upsertAndFindProductView() {
        String productId = UUID.randomUUID().toString();
        productViewRepository.upsert(new ProductView(
                productId, "Hausrat Basic", "HOUSEHOLD_CONTENTS",
                new BigDecimal("120.00"), true));

        var found = productViewRepository.findById(productId);
        assertTrue(found.isPresent(), "ProductView should be found after upsert");
        assertEquals("Hausrat Basic", found.get().getName());
        assertTrue(found.get().isActive());
    }

    @Test
    @DisplayName("Upsert overwrites existing ProductView")
    void upsertOverwritesExisting() {
        String productId = UUID.randomUUID().toString();
        productViewRepository.upsert(new ProductView(
                productId, "Original", "LIABILITY",
                new BigDecimal("100.00"), true));
        productViewRepository.upsert(new ProductView(
                productId, "Updated", "LIABILITY",
                new BigDecimal("150.00"), true));

        var found = productViewRepository.findById(productId);
        assertTrue(found.isPresent());
        assertEquals("Updated", found.get().getName());
        assertEquals(0, new BigDecimal("150.00").compareTo(found.get().getBasePremium()),
            "Base premium should be updated to 150.00");
    }

    @Test
    @DisplayName("Deactivate sets active flag to false")
    void deactivateProduct() {
        String productId = UUID.randomUUID().toString();
        productViewRepository.upsert(new ProductView(
                productId, "To Deactivate", "TRAVEL",
                new BigDecimal("80.00"), true));

        productViewRepository.deactivate(productId);

        var found = productViewRepository.findById(productId);
        assertTrue(found.isPresent());
        assertFalse(found.get().isActive(), "Product should be inactive after deactivation");
    }

    @Test
    @DisplayName("FindAllActive returns only active products")
    void findAllActive_excludesInactive() {
        String activeId = UUID.randomUUID().toString();
        String inactiveId = UUID.randomUUID().toString();

        productViewRepository.upsert(new ProductView(
                activeId, "Active Product", "MOTOR_VEHICLE",
                new BigDecimal("200.00"), true));
        productViewRepository.upsert(new ProductView(
                inactiveId, "Inactive Product", "MOTOR_VEHICLE",
                new BigDecimal("200.00"), true));
        productViewRepository.deactivate(inactiveId);

        var activeProducts = productViewRepository.findAllActive();
        assertTrue(activeProducts.stream().anyMatch(p -> p.getProductId().equals(activeId)),
            "Active product should be in the result");
        assertFalse(activeProducts.stream().anyMatch(p -> p.getProductId().equals(inactiveId)),
            "Inactive product should NOT be in the result");
    }

    @Test
    @DisplayName("FindById returns empty for unknown product")
    void findById_unknown_returnsEmpty() {
        var result = productViewRepository.findById("nonexistent-product-id");
        assertTrue(result.isEmpty(), "Unknown productId should return empty Optional");
    }
}
