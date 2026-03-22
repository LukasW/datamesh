package ch.yuno.billing.infrastructure.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_line_item")
public class InvoiceLineItemEntity {

    @Id
    @Column(name = "line_item_id", length = 36)
    private String lineItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private InvoiceEntity invoice;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    public String getLineItemId() { return lineItemId; }
    public void setLineItemId(String lineItemId) { this.lineItemId = lineItemId; }
    public InvoiceEntity getInvoice() { return invoice; }
    public void setInvoice(InvoiceEntity invoice) { this.invoice = invoice; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
