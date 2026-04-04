package ch.yuno.billing.infrastructure.messaging;

import ch.yuno.billing.domain.model.BillingCycle;
import ch.yuno.billing.domain.port.out.InvoiceLineItemLabelProvider;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class InvoiceLineItemLabelAdapter implements InvoiceLineItemLabelProvider {
    @Override
    public String labelForCycle(BillingCycle cycle) {
        return InvoiceLineItemLabels.forCycle(cycle);
    }
}
