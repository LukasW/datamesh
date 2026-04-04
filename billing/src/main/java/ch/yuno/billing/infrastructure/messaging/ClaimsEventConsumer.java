package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.port.in.InvoiceCommandUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Consumes claims.v1.settled events and triggers a payout via the billing domain.
 * Part of the Outbox Pattern: the resulting OutboxEvent is persisted and picked up by Debezium.
 */
@ApplicationScoped
public class ClaimsEventConsumer {

    private static final Logger LOG = Logger.getLogger(ClaimsEventConsumer.class);

    @Inject
    InvoiceCommandUseCase invoiceCommandService;

    @Incoming("claims-settled-in")
    public void onClaimSettled(String payload) {
        try {
            String claimId    = extractField(payload, "claimId");
            String policyId   = extractField(payload, "policyId");
            String partnerId  = extractField(payload, "partnerId");
            String amountStr  = extractField(payload, "settlementAmount");

            if (claimId == null || policyId == null || partnerId == null || amountStr == null) {
                LOG.warnf("Skipping malformed claims.v1.settled event: %s", payload);
                return;
            }

            BigDecimal settlementAmount = new BigDecimal(amountStr);
            invoiceCommandService.triggerPayout(claimId, policyId, partnerId, settlementAmount);
            LOG.infof("Payout triggered for claimId=%s policyId=%s partnerId=%s amount=%s",
                    claimId, policyId, partnerId, settlementAmount);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process claims.v1.settled event: %s", payload);
            throw e;
        }
    }

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
