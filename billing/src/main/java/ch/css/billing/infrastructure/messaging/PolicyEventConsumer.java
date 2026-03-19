package ch.css.billing.infrastructure.messaging;

import ch.css.billing.domain.model.BillingCycle;
import ch.css.billing.domain.service.InvoiceCommandService;
import ch.css.billing.infrastructure.messaging.acl.PolicyEventTranslator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Kafka consumer for Policy domain events.
 * Materialises invoices from policy lifecycle events (Data Outside pattern).
 * Does not query policy-db directly — only consumes from Kafka topics.
 */
@ApplicationScoped
public class PolicyEventConsumer {

    private static final Logger log = Logger.getLogger(PolicyEventConsumer.class);

    @Inject
    InvoiceCommandService invoiceCommandService;

    @Incoming("policy-issued-in")
    public void onPolicyIssued(String message) {
        try {
            PolicyEventTranslator.PolicyIssuedData data = PolicyEventTranslator.translateIssued(message);
            String invoiceId = invoiceCommandService.createInvoiceForPolicy(
                    data.policyId(), data.policyNumber(), data.partnerId(),
                    data.premium(), BillingCycle.ANNUAL);
            log.infof("Invoice created: %s for policy %s", invoiceId, data.policyNumber());
        } catch (Exception e) {
            log.errorf("Failed to process PolicyIssued: %s", e.getMessage());
            throw e;
        }
    }

    @Incoming("policy-cancelled-in")
    public void onPolicyCancelled(String message) {
        try {
            PolicyEventTranslator.PolicyCancelledData data = PolicyEventTranslator.translateCancelled(message);
            invoiceCommandService.cancelInvoicesForPolicy(data.policyId());
            log.infof("Invoices cancelled for policy %s", data.policyId());
        } catch (Exception e) {
            log.errorf("Failed to process PolicyCancelled: %s", e.getMessage());
            throw e;
        }
    }
}
