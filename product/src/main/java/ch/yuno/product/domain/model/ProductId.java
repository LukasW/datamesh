package ch.yuno.product.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed value object for Product identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record ProductId(String value) {

    public ProductId {
        Objects.requireNonNull(value, "ProductId must not be null");
    }

    public static ProductId generate() {
        return new ProductId(UUID.randomUUID().toString());
    }

    public static ProductId of(String value) {
        return new ProductId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
