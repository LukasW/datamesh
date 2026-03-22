package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.model.BillingCycle;

/**
 * German UI labels for invoice line items (ADR-005: German strings belong outside the domain layer).
 */
public final class InvoiceLineItemLabels {

    private InvoiceLineItemLabels() {}

    public static String forCycle(BillingCycle cycle) {
        return switch (cycle) {
            case ANNUAL      -> "Jahresprämie";
            case SEMI_ANNUAL -> "Halbjahresprämie";
            case QUARTERLY   -> "Quartalsprämie";
            case MONTHLY     -> "Monatsprämie";
        };
    }
}
