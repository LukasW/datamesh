package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.port.in.InvoiceCommandUseCase;
import ch.yuno.billing.domain.port.out.InvoiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Consumes claims.v1.settled events and triggers a payout via the billing domain.
 * Part of the Outbox Pattern: the resulting OutboxEvent is persisted and picked up by Debezium.
 *
 * partnerId is not part of the claims.v1.settled ODC contract — it is resolved
 * from billing's local Invoice read-model via policyId.
 */
@ApplicationScoped
public class ClaimsEventConsumer {

    private static final Logger LOG = Logger.getLogger(ClaimsEventConsumer.class);

    @Inject
    InvoiceCommandUseCase invoiceCommandService;

    @Inject
    InvoiceRepository invoiceRepository;

    @Incoming("claims-settled-in")
    @Transactional
    public void onClaimSettled(String payload) {
        try {
            String claimId   = extractStringField(payload, "claimId");
            String policyId  = extractStringField(payload, "policyId");
            String amountStr = extractNumericField(payload, "settlementAmount");

            if (claimId == null || policyId == null || amountStr == null) {
                LOG.warnf("Skipping malformed claims.v1.settled event: %s", payload);
                return;
            }

            List<Invoice> invoices = invoiceRepository.findByPolicyId(policyId);
            if (invoices.isEmpty()) {
                LOG.warnf("No invoice found for policyId=%s — cannot resolve partnerId; skipping payout for claimId=%s",
                        policyId, claimId);
                return;
            }
            String partnerId = invoices.get(0).getPartnerId();

            BigDecimal settlementAmount = new BigDecimal(amountStr);
            invoiceCommandService.triggerPayout(claimId, policyId, partnerId, settlementAmount);
            LOG.infof("Payout triggered for claimId=%s policyId=%s partnerId=%s amount=%s",
                    claimId, policyId, partnerId, settlementAmount);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process claims.v1.settled event: %s", payload);
            throw e;
        }
    }

    private static String extractStringField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /**
     * Extracts a JSON numeric field value (e.g. "settlementAmount":1234.56).
     * Matches digits, minus, and a single dot. Returns null when the field
     * is absent or non-numeric.
     */
    private static String extractNumericField(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                end++;
            } else {
                break;
            }
        }
        if (end == start) return null;
        return json.substring(start, end);
    }
}
