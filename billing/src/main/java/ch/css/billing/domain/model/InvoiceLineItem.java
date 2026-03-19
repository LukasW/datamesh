package ch.css.billing.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entity: a single line on an invoice (e.g. annual premium, installment).
 * No framework dependencies.
 */
public class InvoiceLineItem {

    private final String lineItemId;
    private final String description;
    private final BigDecimal amount;

    /** Constructor for creating a new line item. */
    public InvoiceLineItem(String description, BigDecimal amount) {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description is required");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("amount must be positive");
        this.lineItemId = UUID.randomUUID().toString();
        this.description = description;
        this.amount = amount;
    }

    /** Constructor for reconstructing from persistence. */
    public InvoiceLineItem(String lineItemId, String description, BigDecimal amount) {
        this.lineItemId = lineItemId;
        this.description = description;
        this.amount = amount;
    }

    public String getLineItemId() { return lineItemId; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
}
