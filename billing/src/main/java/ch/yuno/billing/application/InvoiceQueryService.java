package ch.yuno.billing.application;

import ch.yuno.billing.domain.model.Invoice;
import ch.yuno.billing.domain.model.InvoiceId;
import ch.yuno.billing.domain.model.InvoiceStatus;
import ch.yuno.billing.domain.model.PolicyholderView;
import ch.yuno.billing.domain.port.in.InvoiceQueryUseCase;
import ch.yuno.billing.domain.port.out.InvoiceRepository;
import ch.yuno.billing.domain.port.out.PolicyholderViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class InvoiceQueryService implements InvoiceQueryUseCase {

    @Inject
    InvoiceRepository invoiceRepository;

    @Inject
    PolicyholderViewRepository policyholderViewRepository;

    public Invoice findByIdOrThrow(InvoiceId invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId.value()));
    }

    public List<Invoice> listAll(int page, int size) {
        return invoiceRepository.findAll(page, size);
    }

    public List<Invoice> listByStatus(InvoiceStatus status, int page, int size) {
        return invoiceRepository.findByStatus(status, page, size);
    }

    public List<Invoice> listByPartnerId(String partnerId, int page, int size) {
        return invoiceRepository.findByPartnerId(partnerId, page, size);
    }

    public long countAll() {
        return invoiceRepository.countAll();
    }

    public Optional<PolicyholderView> findPolicyholder(String partnerId) {
        return policyholderViewRepository.findById(partnerId);
    }

    public Optional<PolicyholderView> findPolicyholderByInsuredNumber(String insuredNumber) {
        return policyholderViewRepository.findByInsuredNumber(insuredNumber);
    }
}
