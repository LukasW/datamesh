package ch.yuno.billing.domain.model;
import java.util.Objects;
import java.util.UUID;
/**
 * Typed value object for Invoice identifiers.
 * Prevents accidental mixing of different ID types at compile time.
 */
public record InvoiceId(String value) {
    public InvoiceId {
        Objects.requireNonNull(value, "InvoiceId must not be null");
    }
    public static InvoiceId generate() {
        return new InvoiceId(UUID.randomUUID().toString());
    }
    public static InvoiceId of(String value) {
        return new InvoiceId(value);
    }
    @Override
    public String toString() {
        return value;
    }
}
