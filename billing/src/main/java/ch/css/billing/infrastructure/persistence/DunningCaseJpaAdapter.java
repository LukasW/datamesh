package ch.css.billing.infrastructure.persistence;

import ch.css.billing.domain.model.DunningCase;
import ch.css.billing.domain.model.DunningLevel;
import ch.css.billing.domain.port.out.DunningCaseRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class DunningCaseJpaAdapter implements DunningCaseRepository {

    @Inject
    EntityManager em;

    @Override
    public void save(DunningCase dunningCase) {
        DunningCaseEntity entity = em.find(DunningCaseEntity.class, dunningCase.getDunningCaseId());
        if (entity == null) {
            entity = new DunningCaseEntity();
            entity.setDunningCaseId(dunningCase.getDunningCaseId());
            entity.setInvoiceId(dunningCase.getInvoiceId());
            entity.setInitiatedAt(dunningCase.getInitiatedAt());
        }
        entity.setLevel(dunningCase.getLevel().name());
        entity.setEscalatedAt(dunningCase.getEscalatedAt());
        em.merge(entity);
    }

    @Override
    public Optional<DunningCase> findByInvoiceId(String invoiceId) {
        List<DunningCaseEntity> results = em.createQuery(
                "SELECT d FROM DunningCaseEntity d WHERE d.invoiceId = :invoiceId",
                DunningCaseEntity.class)
                .setParameter("invoiceId", invoiceId)
                .getResultList();
        return results.stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<DunningCase> findById(String dunningCaseId) {
        return Optional.ofNullable(em.find(DunningCaseEntity.class, dunningCaseId))
                .map(this::toDomain);
    }

    private DunningCase toDomain(DunningCaseEntity e) {
        return new DunningCase(
                e.getDunningCaseId(), e.getInvoiceId(),
                DunningLevel.valueOf(e.getLevel()),
                e.getInitiatedAt(), e.getEscalatedAt());
    }
}
