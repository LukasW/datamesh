package ch.yuno.product.application;

import ch.yuno.product.domain.model.PageRequest;
import ch.yuno.product.domain.model.PageResult;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.port.in.ProductQueryUseCase;
import ch.yuno.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ProductQueryService implements ProductQueryUseCase {

    @Inject
    ProductRepository productRepository;

    public Product findById(ProductId productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId.value()));
    }

    public List<Product> listAllProducts() {
        return productRepository.findAll();
    }

    public List<Product> searchProducts(String name, ProductLine productLine) {
        return productRepository.search(name, productLine);
    }

    public PageResult<Product> searchProducts(String name, ProductLine productLine, PageRequest pageRequest) {
        return productRepository.search(name, productLine, pageRequest);
    }
}
