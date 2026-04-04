package ch.yuno.product.domain.port.in;

import ch.yuno.product.domain.model.PageRequest;
import ch.yuno.product.domain.model.PageResult;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;

import java.util.List;

/**
 * Inbound port for product query use cases.
 */
public interface ProductQueryUseCase {

    Product findById(ProductId productId);

    List<Product> listAllProducts();

    List<Product> searchProducts(String name, ProductLine productLine);

    PageResult<Product> searchProducts(String name, ProductLine productLine, PageRequest pageRequest);
}
