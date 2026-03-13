package ch.css.partner.infrastructure.persistence;

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
 * Reads Hibernate Envers audit history for Person and Adresse entities.
 */
@ApplicationScoped
public class PersonAuditAdapter {

    @Inject
    EntityManager em;

    public List<PersonRevisionRecord> getPersonHistory(String personId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(PersonEntity.class, false, true)
                .add(AuditEntity.id().eq(personId))
                .getResultList();
        return rows.stream().map(this::toPersonRecord).toList();
    }

    public List<AdresseRevisionRecord> getAdresseHistory(String adressId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(AdresseEntity.class, false, true)
                .add(AuditEntity.id().eq(adressId))
                .getResultList();
        return rows.stream().map(this::toAdresseRecord).toList();
    }

    private PersonRevisionRecord toPersonRecord(Object[] row) {
        PersonEntity entity = (PersonEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> zustand = (entity != null) ? Map.of(
                "name", nvl(entity.getName()),
                "vorname", nvl(entity.getVorname()),
                "geschlecht", nvl(entity.getGeschlecht()),
                "geburtsdatum", nvl(entity.getGeburtsdatum()),
                "ahvNummer", nvl(entity.getAhvNummer())
        ) : Map.of();

        return new PersonRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, zustand);
    }

    private AdresseRevisionRecord toAdresseRecord(Object[] row) {
        AdresseEntity entity = (AdresseEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> zustand = (entity != null) ? Map.of(
                "adressTyp", nvl(entity.getAdressTyp()),
                "strasse", nvl(entity.getStrasse()),
                "hausnummer", nvl(entity.getHausnummer()),
                "plz", nvl(entity.getPlz()),
                "ort", nvl(entity.getOrt()),
                "gueltigVon", nvl(entity.getGueltigVon()),
                "gueltigBis", entity.getGueltigBis() != null ? entity.getGueltigBis().toString() : null
        ) : Map.of();

        return new AdresseRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, zustand);
    }

    private long revisionNumber(Object revEntity) {
        try {
            return (long) revEntity.getClass().getMethod("getRev").invoke(revEntity);
        } catch (Exception e) {
            return -1L;
        }
    }

    private LocalDateTime revisionTimestamp(Object revEntity) {
        try {
            long millis = (long) revEntity.getClass().getMethod("getRevtstmp").invoke(revEntity);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private String changedBy(Object revEntity) {
        try {
            return (String) revEntity.getClass().getMethod("getChangedBy").invoke(revEntity);
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

    public record PersonRevisionRecord(
            long revisionNummer,
            String revisionTyp,
            LocalDateTime geaendertAm,
            String geaendertVon,
            Map<String, Object> zustand
    ) {}

    public record AdresseRevisionRecord(
            long revisionNummer,
            String revisionTyp,
            LocalDateTime geaendertAm,
            String geaendertVon,
            Map<String, Object> zustand
    ) {}
}
