package ch.css.product.domain.service;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.port.out.OutboxRepository;
import ch.css.product.domain.port.out.ProductRepository;
import ch.css.product.infrastructure.messaging.ProductEventPayloadBuilder;
import ch.css.product.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class ProductCommandService {

    @Inject
    ProductRepository productRepository;

    @Inject
    OutboxRepository outboxRepository;

    @Transactional
    public String defineProduct(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        Product product = new Product(name, description, productLine, basePremium);
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId(), "ProductDefined",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEFINED,
                ProductEventPayloadBuilder.buildProductDefined(
                        product.getProductId(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        product.getProductId(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product.getProductId();
    }

    @Transactional
    public Product updateProduct(String productId, String name, String description,
                                 ProductLine productLine, BigDecimal basePremium) {
        Product product = findOrThrow(productId);
        product.update(name, description, productLine, basePremium);
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId, "ProductUpdated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_UPDATED,
                ProductEventPayloadBuilder.buildProductUpdated(
                        productId, product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId, "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        productId, product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product;
    }

    @Transactional
    public Product deprecateProduct(String productId) {
        Product product = findOrThrow(productId);
        product.deprecate();
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId, "ProductDeprecated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEPRECATED,
                ProductEventPayloadBuilder.buildProductDeprecated(productId)));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId, "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        productId, product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product;
    }

    @Transactional
    public void deleteProduct(String productId) {
        findOrThrow(productId);
        productRepository.delete(productId);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId, "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductStateDeleted(productId)));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Product findOrThrow(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
