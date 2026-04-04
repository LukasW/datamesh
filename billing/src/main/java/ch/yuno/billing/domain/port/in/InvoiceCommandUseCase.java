package ch.yuno.billing.domain.port.in;

import ch.yuno.billing.domain.model.BillingCycle;
import ch.yuno.billing.domain.model.InvoiceId;

import java.math.BigDecimal;

/**
 * Inbound port for invoice command use cases.
 */
public interface InvoiceCommandUseCase {

    InvoiceId createInvoiceForPolicy(String policyId, String policyNumber,
                                     String partnerId, BigDecimal annualPremium,
                                     BillingCycle billingCycle);

    void recordPayment(InvoiceId invoiceId);

    String initiateDunning(InvoiceId invoiceId);

    void cancelInvoicesForPolicy(String policyId);

    void triggerPayout(String claimId, String policyId, String partnerId, BigDecimal settlementAmount);
}
