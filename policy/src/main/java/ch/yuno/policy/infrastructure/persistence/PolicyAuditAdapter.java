package ch.yuno.policy.infrastructure.persistence;

import ch.yuno.policy.infrastructure.persistence.audit.CustomRevisionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Reads Hibernate Envers audit history for Policy and Coverage entities.
 */
@ApplicationScoped
public class PolicyAuditAdapter {

    @Inject
    EntityManager em;

    public List<PolicyRevisionRecord> getPolicyHistory(String policyId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(PolicyEntity.class, false, true)
                .add(AuditEntity.id().eq(policyId))
                .getResultList();
        return rows.stream().map(this::toPolicyRecord).toList();
    }

    public List<CoverageRevisionRecord> getCoverageHistory(String coverageId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(CoverageEntity.class, false, true)
                .add(AuditEntity.id().eq(coverageId))
                .getResultList();
        return rows.stream().map(this::toCoverageRecord).toList();
    }

    private PolicyRevisionRecord toPolicyRecord(Object[] row) {
        PolicyEntity entity = (PolicyEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> state = (entity != null) ? Map.of(
                "policyNumber", nvl(entity.getPolicyNumber()),
                "partnerId", nvl(entity.getPartnerId()),
                "productId", nvl(entity.getProductId()),
                "status", nvl(entity.getStatus()),
                "coverageStartDate", nvl(entity.getCoverageStartDate()),
                "premium", nvl(entity.getPremium()),
                "deductible", nvl(entity.getDeductible())
        ) : Map.of();

        return new PolicyRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, state);
    }

    private CoverageRevisionRecord toCoverageRecord(Object[] row) {
        CoverageEntity entity = (CoverageEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> state = (entity != null) ? Map.of(
                "coverageType", nvl(entity.getCoverageType()),
                "insuredAmount", nvl(entity.getInsuredAmount())
        ) : Map.of();

        return new CoverageRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, state);
    }

    private long revisionNumber(Object revEntity) {
        try {
            return ((CustomRevisionEntity) revEntity).getRev();
        } catch (Exception e) {
            return -1L;
        }
    }

    private LocalDateTime revisionTimestamp(Object revEntity) {
        try {
            long millis = ((CustomRevisionEntity) revEntity).getRevtstmp();
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private String changedBy(Object revEntity) {
        try {
            return ((CustomRevisionEntity) revEntity).getChangedBy();
        } catch (Exception e) {
            return null;
        }
    }

    private String revTypeName(RevisionType rt) {
        return switch (rt) {
            case ADD -> "INSERT";
            case MOD -> "UPDATE";
            case DEL -> "DELETE";
        };
    }

    private Object nvl(Object v) {
        return v != null ? v.toString() : null;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record PolicyRevisionRecord(
            long revisionNumber,
            String revisionType,
            LocalDateTime changedAt,
            String changedBy,
            Map<String, Object> state
    ) {}

    public record CoverageRevisionRecord(
            long revisionNumber,
            String revisionType,
            LocalDateTime changedAt,
            String changedBy,
            Map<String, Object> state
    ) {}
}
