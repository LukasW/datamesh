package ch.yuno.product.domain.service;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.port.out.OutboxRepository;
import ch.yuno.product.domain.port.out.ProductRepository;
import ch.yuno.product.infrastructure.messaging.ProductEventPayloadBuilder;
import ch.yuno.product.infrastructure.messaging.outbox.OutboxEvent;
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
    public ProductId defineProduct(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        Product product = new Product(name, description, productLine, basePremium);
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductDefined",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEFINED,
                ProductEventPayloadBuilder.buildProductDefined(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", product.getProductId().value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        product.getProductId().value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product.getProductId();
    }

    @Transactional
    public Product updateProduct(ProductId productId, String name, String description,
                                 ProductLine productLine, BigDecimal basePremium) {
        Product product = findOrThrow(productId);
        product.update(name, description, productLine, basePremium);
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductUpdated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_UPDATED,
                ProductEventPayloadBuilder.buildProductUpdated(
                        productId.value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        productId.value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product;
    }

    @Transactional
    public Product deprecateProduct(ProductId productId) {
        Product product = findOrThrow(productId);
        product.deprecate();
        productRepository.save(product);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductDeprecated",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_DEPRECATED,
                ProductEventPayloadBuilder.buildProductDeprecated(productId.value())));
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductState(
                        productId.value(), product.getName(),
                        product.getProductLine().name(), product.getBasePremium(),
                        product.getStatus().name())));
        return product;
    }

    @Transactional
    public void deleteProduct(ProductId productId) {
        findOrThrow(productId);
        productRepository.delete(productId);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "product", productId.value(), "ProductState",
                ProductEventPayloadBuilder.TOPIC_PRODUCT_STATE,
                ProductEventPayloadBuilder.buildProductStateDeleted(productId.value())));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Product findOrThrow(ProductId productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.value()));
    }
}
