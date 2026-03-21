package ch.css.claims.infrastructure.messaging;

import ch.css.claims.domain.model.PolicySnapshot;
import ch.css.claims.domain.port.out.PolicySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Kafka consumer for policy.v1.issued events.
 * Materialises a local PolicySnapshot read model used for FNOL coverage checks (ADR-008).
 * No REST call to the Policy service is needed.
 */
@ApplicationScoped
public class PolicyIssuedConsumer {

    private static final Logger LOG = Logger.getLogger(PolicyIssuedConsumer.class);

    @Inject
    PolicySnapshotRepository policySnapshotRepository;

    @Incoming("policy-issued-in")
    @Transactional
    public void onPolicyIssued(String payload) {
        try {
            String policyId             = extractField(payload, "policyId");
            String policyNumber         = extractField(payload, "policyNumber");
            String partnerId            = extractField(payload, "partnerId");
            String productId            = extractField(payload, "productId");
            String coverageStartDateStr = extractField(payload, "coverageStartDate");
            String premiumStr           = extractField(payload, "premium");

            if (policyId == null || partnerId == null || coverageStartDateStr == null || premiumStr == null) {
                LOG.warnf("Skipping malformed policy.v1.issued event: %s", payload);
                return;
            }

            PolicySnapshot snapshot = new PolicySnapshot(
                    policyId,
                    policyNumber,
                    partnerId,
                    productId,
                    LocalDate.parse(coverageStartDateStr),
                    new BigDecimal(premiumStr)
            );
            policySnapshotRepository.upsert(snapshot);
            LOG.infof("Policy snapshot stored: policyId=%s policyNumber=%s", policyId, policyNumber);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process policy.v1.issued event: %s", payload);
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
