package ch.yuno.product.domain.port.in;

import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;

import java.math.BigDecimal;

/**
 * Inbound port for product command use cases.
 */
public interface ProductCommandUseCase {

    ProductId defineProduct(String name, String description, ProductLine productLine, BigDecimal basePremium);

    Product updateProduct(ProductId productId, String name, String description,
                          ProductLine productLine, BigDecimal basePremium);

    Product deprecateProduct(ProductId productId);

    void deleteProduct(ProductId productId);
}
