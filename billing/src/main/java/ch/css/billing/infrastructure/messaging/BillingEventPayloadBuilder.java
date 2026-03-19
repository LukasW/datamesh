package ch.css.billing.infrastructure.messaging;

import ch.css.billing.domain.model.DunningCase;
import ch.css.billing.domain.model.Invoice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Builds JSON payloads for outbox events.
 * Manual JSON construction avoids a Jackson dependency in the infrastructure layer.
 */
public final class BillingEventPayloadBuilder {

    public static final String TOPIC_INVOICE_CREATED   = "billing.v1.invoice-created";
    public static final String TOPIC_PAYMENT_RECEIVED  = "billing.v1.payment-received";
    public static final String TOPIC_DUNNING_INITIATED = "billing.v1.dunning-initiated";
    public static final String TOPIC_PAYOUT_TRIGGERED  = "billing.v1.payout-triggered";

    private BillingEventPayloadBuilder() {}

    public static String buildInvoiceCreated(Invoice invoice) {
        return """
                {"eventId":"%s","eventType":"InvoiceCreated","invoiceId":"%s","invoiceNumber":"%s","policyId":"%s","policyNumber":"%s","partnerId":"%s","billingCycle":"%s","totalAmount":"%s","dueDate":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        invoice.getInvoiceId(),
                        invoice.getInvoiceNumber(),
                        invoice.getPolicyId(),
                        invoice.getPolicyNumber(),
                        invoice.getPartnerId(),
                        invoice.getBillingCycle(),
                        invoice.getTotalAmount().toPlainString(),
                        invoice.getDueDate(),
                        OffsetDateTime.now()
                );
    }

    public static String buildPaymentReceived(Invoice invoice) {
        return """
                {"eventId":"%s","eventType":"PaymentReceived","invoiceId":"%s","invoiceNumber":"%s","policyId":"%s","partnerId":"%s","amountPaid":"%s","paidAt":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        invoice.getInvoiceId(),
                        invoice.getInvoiceNumber(),
                        invoice.getPolicyId(),
                        invoice.getPartnerId(),
                        invoice.getTotalAmount().toPlainString(),
                        invoice.getPaidAt(),
                        OffsetDateTime.now()
                );
    }

    public static String buildDunningInitiated(Invoice invoice, DunningCase dunningCase) {
        return """
                {"eventId":"%s","eventType":"DunningInitiated","dunningCaseId":"%s","invoiceId":"%s","invoiceNumber":"%s","policyId":"%s","partnerId":"%s","dunningLevel":"%s","totalAmount":"%s","dueDate":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        dunningCase.getDunningCaseId(),
                        invoice.getInvoiceId(),
                        invoice.getInvoiceNumber(),
                        invoice.getPolicyId(),
                        invoice.getPartnerId(),
                        dunningCase.getLevel(),
                        invoice.getTotalAmount().toPlainString(),
                        invoice.getDueDate(),
                        OffsetDateTime.now()
                );
    }

    public static String buildPayoutTriggered(String claimId, String policyId,
                                               String partnerId, BigDecimal settlementAmount) {
        return """
                {"eventId":"%s","eventType":"PayoutTriggered","claimId":"%s","policyId":"%s","partnerId":"%s","settlementAmount":"%s","timestamp":"%s"}"""
                .formatted(
                        UUID.randomUUID(),
                        claimId,
                        policyId,
                        partnerId,
                        settlementAmount.toPlainString(),
                        OffsetDateTime.now()
                );
    }
}
