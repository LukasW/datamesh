package ch.css.product.domain.service;

import ch.css.product.domain.model.OutboxEvent;
import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.port.out.OutboxRepository;
import ch.css.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProductApplicationService {

    @Inject
    ProductRepository productRepository;

    @Inject
    OutboxRepository outboxRepository;

    // ── Product CRUD ──────────────────────────────────────────────────────────

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
        return product;
    }

    @Transactional
    public void deleteProduct(String productId) {
        findOrThrow(productId);
        productRepository.delete(productId);
    }

    public Product findById(String productId) {
        return findOrThrow(productId);
    }

    public List<Product> listAllProducts() {
        return productRepository.findAll();
    }

    public List<Product> searchProducts(String name, ProductLine productLine) {
        return productRepository.search(name, productLine);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Product findOrThrow(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
