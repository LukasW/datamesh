package ch.yuno.billing.domain.service;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(String invoiceId) {
        super("Invoice not found: " + invoiceId);
    }
}
