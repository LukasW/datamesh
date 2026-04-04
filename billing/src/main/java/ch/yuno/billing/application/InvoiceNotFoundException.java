package ch.yuno.billing.application;

public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(String invoiceId) {
        super("Invoice not found: " + invoiceId);
    }
}
