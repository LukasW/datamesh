package ch.css.product.domain.service;

import ch.css.product.domain.model.PageRequest;
import ch.css.product.domain.model.PageResult;
import ch.css.product.domain.model.Product;
import ch.css.product.domain.model.ProductLine;
import ch.css.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ProductQueryService {

    @Inject
    ProductRepository productRepository;

    public Product findById(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
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
