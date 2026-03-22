package ch.yuno.claims.infrastructure.persistence;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimStatus;
import ch.yuno.claims.domain.port.out.ClaimRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.annotation.Priority;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Alternative
@Priority(1)
public class ClaimJpaAdapter implements ClaimRepository {

    @Inject
    EntityManager em;

    @Override
    public Claim save(Claim claim) {
        ClaimEntity entity = em.find(ClaimEntity.class, claim.getClaimId());
        if (entity == null) {
            entity = toEntity(claim);
            em.persist(entity);
        } else {
            entity.setStatus(claim.getStatus().name());
        }
        return claim;
    }

    @Override
    public Optional<Claim> findById(String claimId) {
        ClaimEntity entity = em.find(ClaimEntity.class, claimId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<Claim> findByPolicyId(String policyId) {
        TypedQuery<ClaimEntity> query = em.createQuery(
                "SELECT c FROM ClaimEntity c WHERE c.policyId = :policyId ORDER BY c.createdAt DESC",
                ClaimEntity.class);
        query.setParameter("policyId", policyId);
        return query.getResultList().stream().map(this::toDomain).toList();
    }

    public List<Claim> findAll(int page, int size) {
        TypedQuery<ClaimEntity> query = em.createQuery(
                "SELECT c FROM ClaimEntity c ORDER BY c.createdAt DESC", ClaimEntity.class);
        query.setFirstResult(page * size);
        query.setMaxResults(size);
        return query.getResultList().stream().map(this::toDomain).toList();
    }

    public long countAll() {
        return em.createQuery("SELECT COUNT(c) FROM ClaimEntity c", Long.class)
                .getSingleResult();
    }

    private ClaimEntity toEntity(Claim claim) {
        ClaimEntity e = new ClaimEntity();
        e.setClaimId(claim.getClaimId());
        e.setClaimNumber(claim.getClaimNumber());
        e.setPolicyId(claim.getPolicyId());
        e.setDescription(claim.getDescription());
        e.setClaimDate(claim.getClaimDate());
        e.setStatus(claim.getStatus().name());
        e.setCreatedAt(claim.getCreatedAt());
        return e;
    }

    private Claim toDomain(ClaimEntity e) {
        return Claim.reconstitute(
                e.getClaimId(), e.getPolicyId(), e.getClaimNumber(),
                e.getDescription(), e.getClaimDate(),
                ClaimStatus.valueOf(e.getStatus()), e.getCreatedAt());
    }
}
