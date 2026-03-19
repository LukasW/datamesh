package ch.css.billing.domain.model;

public enum BillingCycle {
    ANNUAL,
    SEMI_ANNUAL,
    QUARTERLY,
    MONTHLY;

    /** Number of installments per year. */
    public int installmentsPerYear() {
        return switch (this) {
            case ANNUAL -> 1;
            case SEMI_ANNUAL -> 2;
            case QUARTERLY -> 4;
            case MONTHLY -> 12;
        };
    }
}
