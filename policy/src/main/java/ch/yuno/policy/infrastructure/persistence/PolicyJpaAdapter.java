package ch.yuno.policy.infrastructure.persistence;

import ch.yuno.policy.domain.model.Coverage;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.PageRequest;
import ch.yuno.policy.domain.model.PageResult;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyStatus;
import ch.yuno.policy.domain.port.out.PolicyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PolicyJpaAdapter implements PolicyRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public Policy save(Policy policy) {
        PolicyEntity entity = em.find(PolicyEntity.class, policy.getPolicyId());
        if (entity == null) {
            entity = toEntity(policy);
            em.persist(entity);
        } else {
            updateEntity(entity, policy);
        }
        return policy;
    }

    @Override
    public Optional<Policy> findById(String policyId) {
        PolicyEntity entity = em.find(PolicyEntity.class, policyId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<Policy> findByPolicyNumber(String policyNumber) {
        List<PolicyEntity> results = em.createQuery(
                "SELECT p FROM PolicyEntity p WHERE p.policyNumber = :nr", PolicyEntity.class)
                .setParameter("nr", policyNumber)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<Policy> search(String policyNumber, String partnerId, PolicyStatus status) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM PolicyEntity p WHERE 1=1");
        if (policyNumber != null && !policyNumber.isBlank())
            jpql.append(" AND LOWER(p.policyNumber) LIKE LOWER(:policyNumber)");
        if (partnerId != null && !partnerId.isBlank())
            jpql.append(" AND p.partnerId = :partnerId");
        if (status != null)
            jpql.append(" AND p.status = :status");
        jpql.append(" ORDER BY p.createdAt DESC");

        TypedQuery<PolicyEntity> query = em.createQuery(jpql.toString(), PolicyEntity.class);
        if (policyNumber != null && !policyNumber.isBlank())
            query.setParameter("policyNumber", "%" + policyNumber + "%");
        if (partnerId != null && !partnerId.isBlank())
            query.setParameter("partnerId", partnerId);
        if (status != null)
            query.setParameter("status", status.name());

        return query.getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<Policy> search(String policyNumber, String partnerId, PolicyStatus status, PageRequest pageRequest) {
        String whereClause = buildPolicyWhereClause(policyNumber, partnerId, status);

        StringBuilder countJpql = new StringBuilder("SELECT COUNT(p) FROM PolicyEntity p WHERE 1=1");
        countJpql.append(whereClause);
        TypedQuery<Long> countQuery = em.createQuery(countJpql.toString(), Long.class);
        setPolicyParameters(countQuery, policyNumber, partnerId, status);
        long totalElements = countQuery.getSingleResult();

        StringBuilder jpql = new StringBuilder("SELECT p FROM PolicyEntity p WHERE 1=1");
        jpql.append(whereClause);
        jpql.append(" ORDER BY p.createdAt DESC");
        TypedQuery<PolicyEntity> query = em.createQuery(jpql.toString(), PolicyEntity.class);
        setPolicyParameters(query, policyNumber, partnerId, status);
        query.setFirstResult(pageRequest.page() * pageRequest.size());
        query.setMaxResults(pageRequest.size());

        List<Policy> content = query.getResultList().stream().map(this::toDomain).toList();
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PageResult<>(content, totalElements, totalPages);
    }

    private String buildPolicyWhereClause(String policyNumber, String partnerId, PolicyStatus status) {
        StringBuilder clause = new StringBuilder();
        if (policyNumber != null && !policyNumber.isBlank())
            clause.append(" AND LOWER(p.policyNumber) LIKE LOWER(:policyNumber)");
        if (partnerId != null && !partnerId.isBlank())
            clause.append(" AND p.partnerId = :partnerId");
        if (status != null)
            clause.append(" AND p.status = :status");
        return clause.toString();
    }

    private <T> void setPolicyParameters(TypedQuery<T> query, String policyNumber, String partnerId, PolicyStatus status) {
        if (policyNumber != null && !policyNumber.isBlank())
            query.setParameter("policyNumber", "%" + policyNumber + "%");
        if (partnerId != null && !partnerId.isBlank())
            query.setParameter("partnerId", partnerId);
        if (status != null)
            query.setParameter("status", status.name());
    }

    @Override
    @Transactional
    public void delete(String policyId) {
        PolicyEntity entity = em.find(PolicyEntity.class, policyId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    public boolean existsByPolicyNumber(String policyNumber) {
        Long count = em.createQuery(
                "SELECT COUNT(p) FROM PolicyEntity p WHERE p.policyNumber = :nr", Long.class)
                .setParameter("nr", policyNumber)
                .getSingleResult();
        return count > 0;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PolicyEntity toEntity(Policy policy) {
        PolicyEntity e = new PolicyEntity();
        e.setPolicyId(policy.getPolicyId());
        e.setPolicyNumber(policy.getPolicyNumber());
        e.setPartnerId(policy.getPartnerId());
        e.setProductId(policy.getProductId());
        e.setStatus(policy.getStatus().name());
        e.setCoverageStartDate(policy.getCoverageStartDate());
        e.setCoverageEndDate(policy.getCoverageEndDate());
        e.setPremium(policy.getPremium());
        e.setDeductible(policy.getDeductible());
        for (Coverage c : policy.getCoverages()) {
            e.getCoverages().add(toCoverageEntity(c, e));
        }
        return e;
    }

    private void updateEntity(PolicyEntity e, Policy policy) {
        e.setProductId(policy.getProductId());
        e.setStatus(policy.getStatus().name());
        e.setCoverageStartDate(policy.getCoverageStartDate());
        e.setCoverageEndDate(policy.getCoverageEndDate());
        e.setPremium(policy.getPremium());
        e.setDeductible(policy.getDeductible());

        // sync coverages: remove deleted, update existing, add new
        e.getCoverages().removeIf(ce ->
                policy.getCoverages().stream().noneMatch(c -> c.getCoverageId().equals(ce.getCoverageId())));
        for (Coverage c : policy.getCoverages()) {
            CoverageEntity existing = e.getCoverages().stream()
                    .filter(ce -> ce.getCoverageId().equals(c.getCoverageId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setInsuredAmount(c.getInsuredAmount());
            } else {
                e.getCoverages().add(toCoverageEntity(c, e));
            }
        }
    }

    private CoverageEntity toCoverageEntity(Coverage c, PolicyEntity policyEntity) {
        CoverageEntity ce = new CoverageEntity();
        ce.setCoverageId(c.getCoverageId());
        ce.setPolicy(policyEntity);
        ce.setCoverageType(c.getCoverageType().name());
        ce.setInsuredAmount(c.getInsuredAmount());
        return ce;
    }

    private Policy toDomain(PolicyEntity e) {
        Policy policy = new Policy(
                e.getPolicyId(),
                e.getPolicyNumber(),
                e.getPartnerId(),
                e.getProductId(),
                PolicyStatus.valueOf(e.getStatus()),
                e.getCoverageStartDate(),
                e.getCoverageEndDate(),
                e.getPremium(),
                e.getDeductible()
        );
        List<Coverage> coverages = new ArrayList<>();
        for (CoverageEntity ce : e.getCoverages()) {
            coverages.add(new Coverage(
                    ce.getCoverageId(),
                    e.getPolicyId(),
                    CoverageType.valueOf(ce.getCoverageType()),
                    ce.getInsuredAmount()
            ));
        }
        policy.setCoverages(coverages);
        return policy;
    }
}
