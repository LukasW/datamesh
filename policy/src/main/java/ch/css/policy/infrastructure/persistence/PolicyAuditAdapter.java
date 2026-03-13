package ch.css.policy.infrastructure.persistence;

import ch.css.policy.infrastructure.persistence.audit.CustomRevisionEntity;
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
 * Reads Hibernate Envers audit history for Policy and Deckung entities.
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

    public List<DeckungRevisionRecord> getDeckungHistory(String deckungId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(DeckungEntity.class, false, true)
                .add(AuditEntity.id().eq(deckungId))
                .getResultList();
        return rows.stream().map(this::toDeckungRecord).toList();
    }

    private PolicyRevisionRecord toPolicyRecord(Object[] row) {
        PolicyEntity entity = (PolicyEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> zustand = (entity != null) ? Map.of(
                "policyNummer", nvl(entity.getPolicyNummer()),
                "partnerId", nvl(entity.getPartnerId()),
                "produktId", nvl(entity.getProduktId()),
                "status", nvl(entity.getStatus()),
                "versicherungsbeginn", nvl(entity.getVersicherungsbeginn()),
                "praemie", nvl(entity.getPraemie()),
                "selbstbehalt", nvl(entity.getSelbstbehalt())
        ) : Map.of();

        return new PolicyRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, zustand);
    }

    private DeckungRevisionRecord toDeckungRecord(Object[] row) {
        DeckungEntity entity = (DeckungEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> zustand = (entity != null) ? Map.of(
                "deckungstyp", nvl(entity.getDeckungstyp()),
                "versicherungssumme", nvl(entity.getVersicherungssumme())
        ) : Map.of();

        return new DeckungRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, zustand);
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
            long revisionNummer,
            String revisionTyp,
            LocalDateTime geaendertAm,
            String geaendertVon,
            Map<String, Object> zustand
    ) {}

    public record DeckungRevisionRecord(
            long revisionNummer,
            String revisionTyp,
            LocalDateTime geaendertAm,
            String geaendertVon,
            Map<String, Object> zustand
    ) {}
}

