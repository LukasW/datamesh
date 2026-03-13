package ch.css.product.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregate Root: Product.
 * Represents an insurance product offering with its coverage line and base premium.
 * All state changes go through this aggregate.
 */
public class Product {

    private final String productId;
    private String name;
    private String description;
    private ProductLine productLine;
    private BigDecimal basePremium;
    private ProductStatus status;

    /** Constructor for creating a new Product. */
    public Product(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        validate(name, productLine, basePremium);
        this.productId = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
        this.productLine = productLine;
        this.basePremium = basePremium;
        this.status = ProductStatus.ACTIVE;
    }

    /** Constructor for reconstructing from persistence. */
    public Product(String productId, String name, String description,
                   ProductLine productLine, BigDecimal basePremium, ProductStatus status) {
        this.productId = productId;
        this.name = name;
        this.description = description;
        this.productLine = productLine;
        this.basePremium = basePremium;
        this.status = status;
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    public void update(String name, String description, ProductLine productLine, BigDecimal basePremium) {
        validate(name, productLine, basePremium);
        this.name = name;
        this.description = description;
        this.productLine = productLine;
        this.basePremium = basePremium;
    }

    public void deprecate() {
        if (this.status == ProductStatus.DEPRECATED) {
            throw new IllegalStateException("Product is already deprecated");
        }
        this.status = ProductStatus.DEPRECATED;
    }

    public boolean isActive() {
        return this.status == ProductStatus.ACTIVE;
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private static void validate(String name, ProductLine productLine, BigDecimal basePremium) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
        if (productLine == null) throw new IllegalArgumentException("Product line is required");
        if (basePremium == null || basePremium.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Base premium must be zero or positive");
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProductLine getProductLine() { return productLine; }
    public BigDecimal getBasePremium() { return basePremium; }
    public ProductStatus getStatus() { return status; }
}
