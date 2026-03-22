package ch.yuno.billing.domain.model;

public enum DunningLevel {
    REMINDER,
    FIRST_WARNING,
    FINAL_WARNING,
    COLLECTION;

    /** Returns the next escalation level, or null if already at maximum. */
    public DunningLevel next() {
        return switch (this) {
            case REMINDER -> FIRST_WARNING;
            case FIRST_WARNING -> FINAL_WARNING;
            case FINAL_WARNING -> COLLECTION;
            case COLLECTION -> null;
        };
    }
}
