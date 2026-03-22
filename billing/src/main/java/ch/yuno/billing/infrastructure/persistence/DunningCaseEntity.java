package ch.yuno.billing.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "dunning_case")
public class DunningCaseEntity {

    @Id
    @Column(name = "dunning_case_id", length = 36)
    private String dunningCaseId;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "level", nullable = false, length = 20)
    private String level;

    @Column(name = "initiated_at", nullable = false)
    private LocalDate initiatedAt;

    @Column(name = "escalated_at")
    private LocalDate escalatedAt;

    public String getDunningCaseId() { return dunningCaseId; }
    public void setDunningCaseId(String dunningCaseId) { this.dunningCaseId = dunningCaseId; }
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public LocalDate getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(LocalDate initiatedAt) { this.initiatedAt = initiatedAt; }
    public LocalDate getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(LocalDate escalatedAt) { this.escalatedAt = escalatedAt; }
}
