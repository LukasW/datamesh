package ch.css.billing.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceTest {

    private Invoice buildInvoice(BillingCycle cycle) {
        return new Invoice("INV-001", "policy-1", "POL-2024-00001",
                "partner-1", cycle, new BigDecimal("1200.00"),
                LocalDate.now(), LocalDate.now().plusDays(30));
    }

    @Test
    void annualBillingCycleTotalEqualsFullPremium() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        assertEquals(new BigDecimal("1200.00"), invoice.getTotalAmount());
    }

    @Test
    void quarterlyBillingCycleTotalIsQuarterPremium() {
        Invoice invoice = buildInvoice(BillingCycle.QUARTERLY);
        assertEquals(new BigDecimal("300.00"), invoice.getTotalAmount());
    }

    @Test
    void monthlyBillingCycleTotalIsTwelfthPremium() {
        Invoice invoice = buildInvoice(BillingCycle.MONTHLY);
        assertEquals(new BigDecimal("100.00"), invoice.getTotalAmount());
    }

    @Test
    void semiAnnualBillingCycleTotalIsHalfPremium() {
        Invoice invoice = buildInvoice(BillingCycle.SEMI_ANNUAL);
        assertEquals(new BigDecimal("600.00"), invoice.getTotalAmount());
    }

    @Test
    void newInvoiceHasOpenStatus() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        assertEquals(InvoiceStatus.OPEN, invoice.getStatus());
    }

    @Test
    void recordPaymentTransitionsToPayd() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.recordPayment();
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
        assertNotNull(invoice.getPaidAt());
    }

    @Test
    void recordPaymentOnPaidInvoiceThrows() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.recordPayment();
        assertThrows(IllegalStateException.class, invoice::recordPayment);
    }

    @Test
    void markOverdueTransitionsFromOpen() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.markOverdue();
        assertEquals(InvoiceStatus.OVERDUE, invoice.getStatus());
    }

    @Test
    void recordPaymentOnOverdueInvoiceSucceeds() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.markOverdue();
        invoice.recordPayment();
        assertEquals(InvoiceStatus.PAID, invoice.getStatus());
    }

    @Test
    void cancelTransitionsToCancel() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.cancel();
        assertEquals(InvoiceStatus.CANCELLED, invoice.getStatus());
        assertNotNull(invoice.getCancelledAt());
    }

    @Test
    void cancelOnPaidInvoiceThrows() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.recordPayment();
        assertThrows(IllegalStateException.class, invoice::cancel);
    }

    @Test
    void addLineItemAppendsItem() {
        Invoice invoice = buildInvoice(BillingCycle.ANNUAL);
        invoice.addLineItem(new InvoiceLineItem("Test", new BigDecimal("100.00")));
        assertEquals(1, invoice.getLineItems().size());
    }
}
