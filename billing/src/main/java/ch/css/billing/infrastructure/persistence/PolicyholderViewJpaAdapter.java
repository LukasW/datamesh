package ch.css.billing.infrastructure.persistence;

import ch.css.billing.domain.model.PolicyholderView;
import ch.css.billing.domain.port.out.PolicyholderViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

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
        em.merge(entity);
    }

    @Override
    public Optional<PolicyholderView> findById(String partnerId) {
        PolicyholderViewEntity entity = em.find(PolicyholderViewEntity.class, partnerId);
        if (entity == null) return Optional.empty();
        return Optional.of(new PolicyholderView(entity.getPartnerId(), entity.getName()));
    }
}
