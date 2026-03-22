package ch.yuno.billing.infrastructure.persistence;

import ch.yuno.billing.domain.model.PolicyholderView;
import ch.yuno.billing.domain.port.out.PolicyholderViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PolicyholderViewJpaAdapter implements PolicyholderViewRepository {

    @Inject
    EntityManager em;

    @Override
    public void upsert(PolicyholderView view) {
        PolicyholderViewEntity entity = em.find(PolicyholderViewEntity.class, view.partnerId());
        if (entity == null) {
            entity = new PolicyholderViewEntity();
            entity.setPartnerId(view.partnerId());
        }
        entity.setName(view.name());
        entity.setInsuredNumber(view.insuredNumber());
        em.merge(entity);
    }

    @Override
    public Optional<PolicyholderView> findById(String partnerId) {
        PolicyholderViewEntity entity = em.find(PolicyholderViewEntity.class, partnerId);
        if (entity == null) return Optional.empty();
        return Optional.of(toDomain(entity));
    }

    @Override
    public Optional<PolicyholderView> findByInsuredNumber(String insuredNumber) {
        List<PolicyholderViewEntity> results = em.createQuery(
                "SELECT e FROM PolicyholderViewEntity e WHERE e.insuredNumber = :vn",
                PolicyholderViewEntity.class)
                .setParameter("vn", insuredNumber)
                .setMaxResults(1)
                .getResultList();
        return results.stream().findFirst().map(this::toDomain);
    }

    private PolicyholderView toDomain(PolicyholderViewEntity entity) {
        return new PolicyholderView(entity.getPartnerId(), entity.getName(), entity.getInsuredNumber());
    }
}
