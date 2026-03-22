package ch.yuno.billing.domain.service;

import ch.yuno.billing.domain.model.BillingCycle;
import ch.yuno.billing.domain.model.DunningCase;
import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceLineItem;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.port.out.DunningCaseRepository;
import ch.yuno.billing.domain.port.out.InvoiceRepository;
import ch.yuno.billing.domain.port.out.OutboxRepository;
import ch.yuno.billing.infrastructure.messaging.BillingEventPayloadBuilder;
import ch.yuno.billing.infrastructure.messaging.InvoiceLineItemLabels;
import ch.yuno.billing.infrastructure.messaging.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class InvoiceCommandService {

    @Inject
    InvoiceRepository invoiceRepository;

    @Inject
    DunningCaseRepository dunningCaseRepository;

    @Inject
    OutboxRepository outboxRepository;

    /**
     * Creates an invoice when a policy is issued (consumed from policy.v1.issued).
     * Defaults to ANNUAL billing cycle.
     */
    @Transactional
    public String createInvoiceForPolicy(String policyId, String policyNumber,
                                         String partnerId, BigDecimal annualPremium,
                                         BillingCycle billingCycle) {
        String invoiceNumber = generateInvoiceNumber();
        LocalDate today = LocalDate.now();
        LocalDate dueDate = today.plusDays(30);

        Invoice invoice = new Invoice(invoiceNumber, policyId, policyNumber,
                partnerId, billingCycle, annualPremium, today, dueDate);

        String cycleLabel = InvoiceLineItemLabels.forCycle(billingCycle);
        invoice.addLineItem(new InvoiceLineItem(cycleLabel + " " + policyNumber, invoice.getTotalAmount()));

        invoiceRepository.save(invoice);

        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoice.getInvoiceId(), "InvoiceCreated",
                BillingEventPayloadBuilder.TOPIC_INVOICE_CREATED,
                BillingEventPayloadBuilder.buildInvoiceCreated(invoice)));

        return invoice.getInvoiceId();
    }

    /**
     * Records a payment for an invoice. Transitions OPEN/OVERDUE → PAID.
     */
    @Transactional
    public void recordPayment(String invoiceId) {
        Invoice invoice = findOrThrow(invoiceId);
        invoice.recordPayment();
        invoiceRepository.save(invoice);

        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoiceId, "PaymentReceived",
                BillingEventPayloadBuilder.TOPIC_PAYMENT_RECEIVED,
                BillingEventPayloadBuilder.buildPaymentReceived(invoice)));
    }

    /**
     * Initiates dunning for an overdue invoice.
     * If no DunningCase exists yet, creates one at REMINDER level.
     * If one already exists, escalates to the next level.
     */
    @Transactional
    public String initiateDunning(String invoiceId) {
        Invoice invoice = findOrThrow(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.OPEN) {
            invoice.markOverdue();
            invoiceRepository.save(invoice);
        } else if (invoice.getStatus() != InvoiceStatus.OVERDUE) {
            throw new IllegalStateException("Dunning can only be initiated for OPEN or OVERDUE invoices");
        }

        DunningCase dunningCase = dunningCaseRepository.findByInvoiceId(invoiceId)
                .map(existing -> {
                    existing.escalate();
                    return existing;
                })
                .orElseGet(() -> new DunningCase(invoiceId));

        dunningCaseRepository.save(dunningCase);

        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", invoiceId, "DunningInitiated",
                BillingEventPayloadBuilder.TOPIC_DUNNING_INITIATED,
                BillingEventPayloadBuilder.buildDunningInitiated(invoice, dunningCase)));

        return dunningCase.getDunningCaseId();
    }

    /**
     * Cancels all open invoices for a policy (consumed from policy.v1.cancelled).
     */
    @Transactional
    public void cancelInvoicesForPolicy(String policyId) {
        List<Invoice> openInvoices = new java.util.ArrayList<>(invoiceRepository.findByPolicyIdAndStatus(policyId, InvoiceStatus.OPEN));
        openInvoices.addAll(invoiceRepository.findByPolicyIdAndStatus(policyId, InvoiceStatus.OVERDUE));

        for (Invoice invoice : openInvoices) {
            invoice.cancel();
            invoiceRepository.save(invoice);
        }
    }

    /**
     * Triggers a payout event when a claim is settled (consumed from claims.v1.settled).
     * Publishes billing.v1.payout-triggered; actual bank transfer is out of scope.
     */
    @Transactional
    public void triggerPayout(String claimId, String policyId, String partnerId, BigDecimal settlementAmount) {
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID(), "billing", claimId, "PayoutTriggered",
                BillingEventPayloadBuilder.TOPIC_PAYOUT_TRIGGERED,
                BillingEventPayloadBuilder.buildPayoutTriggered(claimId, policyId, partnerId, settlementAmount)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Invoice findOrThrow(String invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
    }

    private String generateInvoiceNumber() {
        int year = LocalDate.now().getYear();
        for (int i = 0; i < 25; i++) {
            int seq = ThreadLocalRandom.current().nextInt(1, 100_000);
            String candidate = "BILL-%d-%05d".formatted(year, seq);
            if (!invoiceRepository.existsByInvoiceNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique invoice number");
    }
}
