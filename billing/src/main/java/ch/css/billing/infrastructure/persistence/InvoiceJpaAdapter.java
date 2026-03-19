package ch.css.billing.infrastructure.persistence;

import ch.css.billing.domain.model.BillingCycle;
import ch.css.billing.domain.model.Invoice;
import ch.css.billing.domain.model.InvoiceLineItem;
import ch.css.billing.domain.model.InvoiceStatus;
import ch.css.billing.domain.port.out.InvoiceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class InvoiceJpaAdapter implements InvoiceRepository {

    @Inject
    EntityManager em;

    @Override
    public void save(Invoice invoice) {
        InvoiceEntity entity = em.find(InvoiceEntity.class, invoice.getInvoiceId());
        if (entity == null) {
            entity = toEntity(invoice);
            syncLineItems(entity, invoice);
            em.persist(entity);
        } else {
            entity.setStatus(invoice.getStatus().name());
            entity.setPaidAt(invoice.getPaidAt());
            entity.setCancelledAt(invoice.getCancelledAt());
            syncLineItems(entity, invoice);
            em.merge(entity);
        }
    }

    @Override
    public Optional<Invoice> findById(String invoiceId) {
        InvoiceEntity entity = em.find(InvoiceEntity.class, invoiceId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<Invoice> findByPolicyId(String policyId) {
        return em.createQuery(
                "SELECT i FROM InvoiceEntity i WHERE i.policyId = :policyId ORDER BY i.invoiceDate DESC",
                InvoiceEntity.class)
                .setParameter("policyId", policyId)
                .getResultList()
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findByPolicyIdAndStatus(String policyId, InvoiceStatus status) {
        return em.createQuery(
                "SELECT i FROM InvoiceEntity i WHERE i.policyId = :policyId AND i.status = :status",
                InvoiceEntity.class)
                .setParameter("policyId", policyId)
                .setParameter("status", status.name())
                .getResultList()
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findAll(int page, int size) {
        return em.createQuery("SELECT i FROM InvoiceEntity i ORDER BY i.invoiceDate DESC", InvoiceEntity.class)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList()
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findByPartnerId(String partnerId, int page, int size) {
        return em.createQuery(
                "SELECT i FROM InvoiceEntity i WHERE i.partnerId = :partnerId ORDER BY i.invoiceDate DESC",
                InvoiceEntity.class)
                .setParameter("partnerId", partnerId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList()
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<Invoice> findByStatus(InvoiceStatus status, int page, int size) {
        return em.createQuery(
                "SELECT i FROM InvoiceEntity i WHERE i.status = :status ORDER BY i.dueDate ASC",
                InvoiceEntity.class)
                .setParameter("status", status.name())
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList()
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAll() {
        return em.createQuery("SELECT COUNT(i) FROM InvoiceEntity i", Long.class)
                .getSingleResult();
    }

    @Override
    public boolean existsByInvoiceNumber(String invoiceNumber) {
        Long count = em.createQuery(
                "SELECT COUNT(i) FROM InvoiceEntity i WHERE i.invoiceNumber = :num", Long.class)
                .setParameter("num", invoiceNumber)
                .getSingleResult();
        return count > 0;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private InvoiceEntity toEntity(Invoice invoice) {
        InvoiceEntity e = new InvoiceEntity();
        e.setInvoiceId(invoice.getInvoiceId());
        e.setInvoiceNumber(invoice.getInvoiceNumber());
        e.setPolicyId(invoice.getPolicyId());
        e.setPolicyNumber(invoice.getPolicyNumber());
        e.setPartnerId(invoice.getPartnerId());
        e.setStatus(invoice.getStatus().name());
        e.setBillingCycle(invoice.getBillingCycle().name());
        e.setTotalAmount(invoice.getTotalAmount());
        e.setInvoiceDate(invoice.getInvoiceDate());
        e.setDueDate(invoice.getDueDate());
        e.setPaidAt(invoice.getPaidAt());
        e.setCancelledAt(invoice.getCancelledAt());
        return e;
    }

    private void syncLineItems(InvoiceEntity entity, Invoice invoice) {
        entity.getLineItems().clear();
        for (InvoiceLineItem item : invoice.getLineItems()) {
            InvoiceLineItemEntity ile = new InvoiceLineItemEntity();
            ile.setLineItemId(item.getLineItemId());
            ile.setInvoice(entity);
            ile.setDescription(item.getDescription());
            ile.setAmount(item.getAmount());
            entity.getLineItems().add(ile);
        }
    }

    private Invoice toDomain(InvoiceEntity e) {
        Invoice invoice = new Invoice(
                e.getInvoiceId(), e.getInvoiceNumber(), e.getPolicyId(), e.getPolicyNumber(),
                e.getPartnerId(), InvoiceStatus.valueOf(e.getStatus()),
                BillingCycle.valueOf(e.getBillingCycle()), e.getTotalAmount(),
                e.getInvoiceDate(), e.getDueDate(), e.getPaidAt(), e.getCancelledAt());

        List<InvoiceLineItem> items = e.getLineItems().stream()
                .map(ile -> new InvoiceLineItem(ile.getLineItemId(), ile.getDescription(), ile.getAmount()))
                .toList();
        invoice.setLineItems(new ArrayList<>(items));
        return invoice;
    }
}
