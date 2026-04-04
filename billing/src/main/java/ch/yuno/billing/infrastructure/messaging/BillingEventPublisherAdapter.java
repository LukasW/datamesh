package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.model.DunningCase;
import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.port.out.BillingEventPublisher;
import ch.yuno.billing.domain.port.out.OutboxRepository;
import ch.yuno.billing.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class BillingEventPublisherAdapter implements BillingEventPublisher {

    @Inject
    OutboxRepository outboxRepository;

    @Override
    public void invoiceCreated(Invoice invoice) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoice.getInvoiceId().value(), "InvoiceCreated",
                BillingEventPayloadBuilder.TOPIC_INVOICE_CREATED,
                BillingEventPayloadBuilder.buildInvoiceCreated(invoice)));
    }

    @Override
    public void paymentReceived(Invoice invoice) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoice.getInvoiceId().value(), "PaymentReceived",
                BillingEventPayloadBuilder.TOPIC_PAYMENT_RECEIVED,
                BillingEventPayloadBuilder.buildPaymentReceived(invoice)));
    }

    @Override
    public void dunningInitiated(Invoice invoice, DunningCase dunningCase) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoice.getInvoiceId().value(), "DunningInitiated",
                BillingEventPayloadBuilder.TOPIC_DUNNING_INITIATED,
                BillingEventPayloadBuilder.buildDunningInitiated(invoice, dunningCase)));
    }

    @Override
    public void payoutTriggered(String claimId, String policyId, String partnerId, BigDecimal settlementAmount) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", claimId, "PayoutTriggered",
                BillingEventPayloadBuilder.TOPIC_PAYOUT_TRIGGERED,
                BillingEventPayloadBuilder.buildPayoutTriggered(claimId, policyId, partnerId, settlementAmount)));
    }
}
