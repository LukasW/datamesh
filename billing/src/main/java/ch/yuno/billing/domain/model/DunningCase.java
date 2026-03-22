package ch.yuno.billing.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate Root: DunningCase (Mahnfall).
 * Tracks the escalation of overdue invoice collection.
 * All state changes go through this aggregate. No framework dependencies.
 */
public class DunningCase {

    private final String dunningCaseId;
    private final String invoiceId;
    private DunningLevel level;
    private final LocalDate initiatedAt;
    private LocalDate escalatedAt;

    /** Constructor for creating a new dunning case (starts at REMINDER). */
    public DunningCase(String invoiceId) {
        if (invoiceId == null || invoiceId.isBlank()) throw new IllegalArgumentException("invoiceId is required");
        this.dunningCaseId = UUID.randomUUID().toString();
        this.invoiceId = invoiceId;
        this.level = DunningLevel.REMINDER;
        this.initiatedAt = LocalDate.now();
    }

    /** Constructor for reconstructing from persistence. */
    public DunningCase(String dunningCaseId, String invoiceId, DunningLevel level,
                       LocalDate initiatedAt, LocalDate escalatedAt) {
        this.dunningCaseId = dunningCaseId;
        this.invoiceId = invoiceId;
        this.level = level;
        this.initiatedAt = initiatedAt;
        this.escalatedAt = escalatedAt;
    }

    /** Escalates the dunning level to the next stage. */
    public void escalate() {
        DunningLevel next = level.next();
        if (next == null) {
            throw new IllegalStateException("Dunning case is already at maximum level: " + level);
        }
        this.level = next;
        this.escalatedAt = LocalDate.now();
    }

    public String getDunningCaseId() { return dunningCaseId; }
    public String getInvoiceId() { return invoiceId; }
    public DunningLevel getLevel() { return level; }
    public LocalDate getInitiatedAt() { return initiatedAt; }
    public LocalDate getEscalatedAt() { return escalatedAt; }
}
