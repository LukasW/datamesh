package ch.yuno.billing.domain.port.in;

import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceId;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.model.PolicyholderView;

import java.util.List;
import java.util.Optional;

/**
 * Inbound port for invoice query use cases.
 */
public interface InvoiceQueryUseCase {

    Invoice findByIdOrThrow(InvoiceId invoiceId);

    List<Invoice> listAll(int page, int size);

    List<Invoice> listByStatus(InvoiceStatus status, int page, int size);

    List<Invoice> listByPartnerId(String partnerId, int page, int size);

    long countAll();

    Optional<PolicyholderView> findPolicyholder(String partnerId);

    Optional<PolicyholderView> findPolicyholderByInsuredNumber(String insuredNumber);
}
