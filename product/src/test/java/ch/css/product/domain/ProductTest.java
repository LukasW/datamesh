package ch.css.product.domain;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.model.ProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ProductTest {

    @Test
    void newProduct_hasActiveStatus() {
        Product product = new Product("Test Produkt", "Beschreibung", ProductLine.HAUSRAT, new BigDecimal("200.00"));

        assertEquals(ProductStatus.ACTIVE, product.getStatus());
        assertTrue(product.isActive());
        assertNotNull(product.getProductId());
    }

    @Test
    void newProduct_requiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("", "desc", ProductLine.HAUSRAT, new BigDecimal("100")));
        assertThrows(IllegalArgumentException.class,
                () -> new Product(null, "desc", ProductLine.HAUSRAT, new BigDecimal("100")));
    }

    @Test
    void newProduct_requiresProductLine() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("Name", "desc", null, new BigDecimal("100")));
    }

    @Test
    void newProduct_rejectsNegativePremium() {
        assertThrows(IllegalArgumentException.class,
                () -> new Product("Name", "desc", ProductLine.HAUSRAT, new BigDecimal("-1")));
    }

    @Test
    void newProduct_allowsZeroPremium() {
        assertDoesNotThrow(() -> new Product("Name", "desc", ProductLine.HAFTPFLICHT, BigDecimal.ZERO));
    }

    @Test
    void update_changesFields() {
        Product product = new Product("Original", "Old desc", ProductLine.HAUSRAT, new BigDecimal("100.00"));

        product.update("Geändert", "New desc", ProductLine.REISE, new BigDecimal("250.00"));

        assertEquals("Geändert", product.getName());
        assertEquals("New desc", product.getDescription());
        assertEquals(ProductLine.REISE, product.getProductLine());
        assertEquals(new BigDecimal("250.00"), product.getBasePremium());
        assertEquals(ProductStatus.ACTIVE, product.getStatus()); // status unchanged
    }

    @Test
    void deprecate_changesStatusToDeprecated() {
        Product product = new Product("Produkt", null, ProductLine.HAFTPFLICHT, new BigDecimal("100.00"));

        product.deprecate();

        assertEquals(ProductStatus.DEPRECATED, product.getStatus());
        assertFalse(product.isActive());
    }

    @Test
    void deprecate_alreadyDeprecated_throws() {
        Product product = new Product("Produkt", null, ProductLine.RECHTSSCHUTZ, new BigDecimal("100.00"));
        product.deprecate();

        assertThrows(IllegalStateException.class, product::deprecate);
    }
}
