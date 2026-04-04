package ch.yuno.product.application;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.port.in.ProductCommandUseCase;
import ch.yuno.product.domain.port.out.ProductEventPublisher;
import ch.yuno.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;

@ApplicationScoped
public class ProductCommandService implements ProductCommandUseCase {

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductEventPublisher productEventPublisher;

    @Transactional
    public ProductId defineProduct(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        Product product = new Product(name, description, productLine, basePremium);
        productRepository.save(product);
        productEventPublisher.productDefined(product);
        return product.getProductId();
    }

    @Transactional
    public Product updateProduct(ProductId productId, String name, String description,
                                 ProductLine productLine, BigDecimal basePremium) {
        Product product = findOrThrow(productId);
        product.update(name, description, productLine, basePremium);
        productRepository.save(product);
        productEventPublisher.productUpdated(product);
        return product;
    }

    @Transactional
    public Product deprecateProduct(ProductId productId) {
        Product product = findOrThrow(productId);
        product.deprecate();
        productRepository.save(product);
        productEventPublisher.productDeprecated(product);
        return product;
    }

    @Transactional
    public void deleteProduct(ProductId productId) {
        findOrThrow(productId);
        productRepository.delete(productId);
        productEventPublisher.productDeleted(productId);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Product findOrThrow(ProductId productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.value()));
    }
}
