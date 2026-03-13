package ch.css.product.domain.service;

import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.port.out.ProductEventPublisher;
import ch.css.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class ProductApplicationService {

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductEventPublisher productEventPublisher;

    // ── Product CRUD ──────────────────────────────────────────────────────────

    @Transactional
    public String defineProduct(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        Product product = new Product(name, description, productLine, basePremium);
        productRepository.save(product);
        productEventPublisher.publishProductDefined(product);
        return product.getProductId();
    }

    @Transactional
    public Product updateProduct(String productId, String name, String description,
                                 ProductLine productLine, BigDecimal basePremium) {
        Product product = findOrThrow(productId);
        product.update(name, description, productLine, basePremium);
        productRepository.save(product);
        productEventPublisher.publishProductUpdated(product);
        return product;
    }

    @Transactional
    public Product deprecateProduct(String productId) {
        Product product = findOrThrow(productId);
        product.deprecate();
        productRepository.save(product);
        productEventPublisher.publishProductDeprecated(productId);
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
