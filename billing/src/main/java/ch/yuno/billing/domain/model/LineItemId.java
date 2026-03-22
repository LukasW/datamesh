package ch.yuno.billing.domain.model;
import java.util.Objects;
import java.util.UUID;
/**
 * Typed value object for InvoiceLineItem identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record LineItemId(String value) {
    public LineItemId {
        Objects.requireNonNull(value, "LineItemId must not be null");
    }
    public static LineItemId generate() {
        return new LineItemId(UUID.randomUUID().toString());
    }
    public static LineItemId of(String value) {
        return new LineItemId(value);
    }
    @Override
    public String toString() {
        return value;
    }
}
