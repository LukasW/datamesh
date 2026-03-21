package ch.css.claims.infrastructure.persistence;

import ch.css.claims.domain.model.PolicySnapshot;
import ch.css.claims.domain.port.out.PolicySnapshotRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Optional;

@ApplicationScoped
public class PolicySnapshotJpaAdapter implements PolicySnapshotRepository {

    @Inject
    EntityManager em;

    @Override
    public void upsert(PolicySnapshot snapshot) {
        PolicySnapshotEntity entity = em.find(PolicySnapshotEntity.class, snapshot.policyId());
        if (entity == null) {
            entity = new PolicySnapshotEntity();
        }
        entity.setPolicyId(snapshot.policyId());
        entity.setPolicyNumber(snapshot.policyNumber());
        entity.setPartnerId(snapshot.partnerId());
        entity.setProductId(snapshot.productId());
        entity.setCoverageStartDate(snapshot.coverageStartDate());
        entity.setPremium(snapshot.premium());
        em.merge(entity);
    }

    @Override
    public Optional<PolicySnapshot> findByPolicyId(String policyId) {
        PolicySnapshotEntity entity = em.find(PolicySnapshotEntity.class, policyId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(entity));
    }

    private PolicySnapshot toDomain(PolicySnapshotEntity e) {
        return new PolicySnapshot(
                e.getPolicyId(),
                e.getPolicyNumber(),
                e.getPartnerId(),
                e.getProductId(),
                e.getCoverageStartDate(),
                e.getPremium()
        );
    }
}
