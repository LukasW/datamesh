package ch.css.product.domain;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.model.ProductStatus;
import ch.css.product.domain.port.out.ProductEventPublisher;
import ch.css.product.domain.port.out.ProductRepository;
import ch.css.product.domain.service.ProductApplicationService;
import ch.css.product.domain.service.ProductNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductApplicationServiceTest {

    @Mock
    ProductRepository productRepository;

    @Mock
    ProductEventPublisher productEventPublisher;

    @InjectMocks
    ProductApplicationService service;

    private Product existingProduct;

    @BeforeEach
    void setUp() {
        existingProduct = new Product("Hausrat Basis", "Beschreibung", ProductLine.HOUSEHOLD_CONTENTS, new BigDecimal("200.00"));
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(existingProduct.getProductId())).thenReturn(Optional.of(existingProduct));
    }

    @Test
    void defineProduct_savesAndPublishesEvent() {
        String id = service.defineProduct("Neues Produkt", "desc", ProductLine.TRAVEL, new BigDecimal("100.00"));

        assertNotNull(id);
        verify(productRepository).save(any(Product.class));
        verify(productEventPublisher).publishProductDefined(any(Product.class));
    }

    @Test
    void updateProduct_updatesAndPublishesEvent() {
        service.updateProduct(existingProduct.getProductId(), "Geändert", "new desc",
                ProductLine.LIABILITY, new BigDecimal("300.00"));

        verify(productRepository).save(existingProduct);
        verify(productEventPublisher).publishProductUpdated(existingProduct);
        assertEquals("Geändert", existingProduct.getName());
    }

    @Test
    void updateProduct_notFound_throws() {
        when(productRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> service.updateProduct("unknown", "x", null, ProductLine.HOUSEHOLD_CONTENTS, BigDecimal.ONE));
    }

    @Test
    void deprecateProduct_deprecatesAndPublishesEvent() {
        service.deprecateProduct(existingProduct.getProductId());

        verify(productRepository).save(existingProduct);
        verify(productEventPublisher).publishProductDeprecated(existingProduct.getProductId());
        assertEquals(ProductStatus.DEPRECATED, existingProduct.getStatus());
    }

    @Test
    void deleteProduct_deletesFromRepository() {
        service.deleteProduct(existingProduct.getProductId());

        verify(productRepository).delete(existingProduct.getProductId());
    }

    @Test
    void listAllProducts_delegatesToRepository() {
        when(productRepository.findAll()).thenReturn(List.of(existingProduct));

        List<Product> result = service.listAllProducts();

        assertEquals(1, result.size());
    }
}
