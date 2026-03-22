package ch.yuno.product.domain;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.model.ProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ProductTest {

    @Test
    void newProduct_hasActiveStatus() {
        Product product = new Product("Test Produkt", "Beschreibung", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("200.00"));

        assertEquals(ProductStatus.ACTIVE, product.getStatus());
        assertTrue(product.isActive());
        assertNotNull(product.getProductId());
    }

    @Test
    void newProduct_requiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("", "desc", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class,
                () -> new Product(null, "desc", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("100")));
    }

    @Test
    void newProduct_requiresProductLine() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("Name", "desc", null, new BigDecimal("100")));
    }

    @Test
    void newProduct_rejectsNegativePremium() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("Name", "desc", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("-1")));
    }

    @Test
    void newProduct_allowsZeroPremium() {
        assertDoesNotThrow(() -> new Product("Name", "desc", ProductLine.LIABILITY, BigDecimal.ZERO));
    }

    @Test
    void update_changesFields() {
        Product product = new Product("Original", "Old desc", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("100.00"));

        product.update("Geändert", "New desc", ProductLine.TRAVEL, new BigDecimal("250.00"));

        assertEquals("Geändert", product.getName());
        assertEquals("New desc", product.getDescription());
        assertEquals(ProductLine.TRAVEL, product.getProductLine());
        assertEquals(new BigDecimal("250.00"), product.getBasePremium());
        assertEquals(ProductStatus.ACTIVE, product.getStatus()); // status unchanged
    }

    @Test
    void deprecate_changesStatusToDeprecated() {
        Product product = new Product("Produkt", null, ProductLine.LIABILITY, new BigDecimal("100.00"));

        product.deprecate();

        assertEquals(ProductStatus.DEPRECATED, product.getStatus());
        assertFalse(product.isActive());
    }

    @Test
    void deprecate_alreadyDeprecated_throws() {
        Product product = new Product("Produkt", null, ProductLine.LEGAL_EXPENSES, new BigDecimal("100.00"));
        product.deprecate();

        assertThrows(IllegalStateException.class, product::deprecate);
    }
}
