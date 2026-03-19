package ch.css.billing.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: Invoice (Rechnung).
 * All state changes go through this aggregate. Invariants are enforced here.
 * No framework dependencies.
 */
public class Invoice {

    private final String invoiceId;
    private final String invoiceNumber;
    private final String policyId;
    private final String policyNumber;
    private final String partnerId;
    private InvoiceStatus status;
    private final BillingCycle billingCycle;
    private final BigDecimal totalAmount;
    private final LocalDate invoiceDate;
    private final LocalDate dueDate;
    private LocalDate paidAt;
    private LocalDate cancelledAt;
    private List<InvoiceLineItem> lineItems;

    /** Constructor for creating a new Invoice. Status starts at OPEN. */
    public Invoice(String invoiceNumber, String policyId, String policyNumber,
                   String partnerId, BillingCycle billingCycle,
                   BigDecimal annualPremium, LocalDate invoiceDate, LocalDate dueDate) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) throw new IllegalArgumentException("invoiceNumber is required");
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("policyId is required");
        if (policyNumber == null || policyNumber.isBlank()) throw new IllegalArgumentException("policyNumber is required");
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId is required");
        if (billingCycle == null) throw new IllegalArgumentException("billingCycle is required");
        if (annualPremium == null || annualPremium.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("annualPremium must be positive");
        if (invoiceDate == null) throw new IllegalArgumentException("invoiceDate is required");
        if (dueDate == null) throw new IllegalArgumentException("dueDate is required");

        this.invoiceId = UUID.randomUUID().toString();
        this.invoiceNumber = invoiceNumber;
        this.policyId = policyId;
        this.policyNumber = policyNumber;
        this.partnerId = partnerId;
        this.billingCycle = billingCycle;
        this.totalAmount = calculateInstallment(annualPremium, billingCycle);
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.status = InvoiceStatus.OPEN;
        this.lineItems = new ArrayList<>();
    }

    /** Constructor for reconstructing from persistence. */
    public Invoice(String invoiceId, String invoiceNumber, String policyId, String policyNumber,
                   String partnerId, InvoiceStatus status, BillingCycle billingCycle,
                   BigDecimal totalAmount, LocalDate invoiceDate, LocalDate dueDate,
                   LocalDate paidAt, LocalDate cancelledAt) {
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.policyId = policyId;
        this.policyNumber = policyNumber;
        this.partnerId = partnerId;
        this.status = status;
        this.billingCycle = billingCycle;
        this.totalAmount = totalAmount;
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.paidAt = paidAt;
        this.cancelledAt = cancelledAt;
        this.lineItems = new ArrayList<>();
    }

    // ── Business Methods ──────────────────────────────────────────────────────

    /** Adds a line item to this invoice. Only allowed when OPEN or OVERDUE. */
    public void addLineItem(InvoiceLineItem item) {
        if (status == InvoiceStatus.PAID || status == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Cannot add line items to a " + status + " invoice");
        }
        lineItems.add(item);
    }

    /** Records a payment. Transitions OPEN/OVERDUE → PAID. */
    public void recordPayment() {
        if (status != InvoiceStatus.OPEN && status != InvoiceStatus.OVERDUE) {
            throw new IllegalStateException("Only OPEN or OVERDUE invoices can be paid (current: " + status + ")");
        }
        this.status = InvoiceStatus.PAID;
        this.paidAt = LocalDate.now();
    }

    /** Marks the invoice as OVERDUE. Only allowed from OPEN. */
    public void markOverdue() {
        if (status != InvoiceStatus.OPEN) {
            throw new IllegalStateException("Only OPEN invoices can be marked OVERDUE (current: " + status + ")");
        }
        this.status = InvoiceStatus.OVERDUE;
    }

    /** Cancels the invoice. Allowed from OPEN or OVERDUE. */
    public void cancel() {
        if (status == InvoiceStatus.PAID || status == InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel a " + status + " invoice");
        }
        this.status = InvoiceStatus.CANCELLED;
        this.cancelledAt = LocalDate.now();
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private static BigDecimal calculateInstallment(BigDecimal annualPremium, BillingCycle cycle) {
        return annualPremium
                .divide(BigDecimal.valueOf(cycle.installmentsPerYear()), 2, RoundingMode.HALF_UP);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getInvoiceId() { return invoiceId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getPolicyId() { return policyId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPartnerId() { return partnerId; }
    public InvoiceStatus getStatus() { return status; }
    public BillingCycle getBillingCycle() { return billingCycle; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getPaidAt() { return paidAt; }
    public LocalDate getCancelledAt() { return cancelledAt; }
    public List<InvoiceLineItem> getLineItems() { return lineItems; }

    /** Used by JPA adapter to restore persisted line items. */
    public void setLineItems(List<InvoiceLineItem> lineItems) {
        this.lineItems = lineItems != null ? new ArrayList<>(lineItems) : new ArrayList<>();
    }
}
