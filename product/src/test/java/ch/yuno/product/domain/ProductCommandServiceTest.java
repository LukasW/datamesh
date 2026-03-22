package ch.yuno.product.domain;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.model.ProductStatus;
import ch.yuno.product.domain.port.out.OutboxRepository;
import ch.yuno.product.domain.port.out.ProductRepository;
import ch.yuno.product.domain.service.ProductCommandService;
import ch.yuno.product.domain.service.ProductNotFoundException;
import ch.yuno.product.infrastructure.messaging.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductCommandServiceTest {

    @Mock
    ProductRepository productRepository;

    @Mock
    OutboxRepository outboxRepository;

    @InjectMocks
    ProductCommandService service;

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
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void updateProduct_updatesAndPublishesEvent() {
        service.updateProduct(existingProduct.getProductId(), "Geändert", "new desc",
                ProductLine.LIABILITY, new BigDecimal("300.00"));

        verify(productRepository).save(existingProduct);
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
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
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
        assertEquals(ProductStatus.DEPRECATED, existingProduct.getStatus());
    }

    @Test
    void deleteProduct_deletesFromRepository() {
        service.deleteProduct(existingProduct.getProductId());

        verify(productRepository).delete(existingProduct.getProductId());
    }
}
