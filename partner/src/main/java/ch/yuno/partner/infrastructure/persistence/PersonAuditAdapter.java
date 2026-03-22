package ch.yuno.partner.infrastructure.persistence;

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
 * Reads Hibernate Envers audit history for Person and Address entities.
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

    public List<AddressRevisionRecord> getAddressHistory(String addressId) {
        AuditReader reader = AuditReaderFactory.get(em);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = reader.createQuery()
                .forRevisionsOfEntity(AddressEntity.class, false, true)
                .add(AuditEntity.id().eq(addressId))
                .getResultList();
        return rows.stream().map(this::toAddressRecord).toList();
    }

    private PersonRevisionRecord toPersonRecord(Object[] row) {
        PersonEntity entity = (PersonEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> state = (entity != null) ? Map.of(
                "name", nvl(entity.getName()),
                "firstName", nvl(entity.getFirstName()),
                "gender", nvl(entity.getGender()),
                "dateOfBirth", nvl(entity.getDateOfBirth()),
                "socialSecurityNumber", nvl(entity.getSocialSecurityNumber())
        ) : Map.of();

        return new PersonRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, state);
    }

    private AddressRevisionRecord toAddressRecord(Object[] row) {
        AddressEntity entity = (AddressEntity) row[0];
        Object revEntity = row[1];
        RevisionType revType = (RevisionType) row[2];

        long revNum = revisionNumber(revEntity);
        LocalDateTime changedAt = revisionTimestamp(revEntity);
        String changedBy = changedBy(revEntity);

        Map<String, Object> state = (entity != null) ? Map.of(
                "addressType", nvl(entity.getAddressType()),
                "street", nvl(entity.getStreet()),
                "houseNumber", nvl(entity.getHouseNumber()),
                "postalCode", nvl(entity.getPostalCode()),
                "city", nvl(entity.getCity()),
                "validFrom", nvl(entity.getValidFrom()),
                "validTo", entity.getValidTo() != null ? entity.getValidTo().toString() : null
        ) : Map.of();

        return new AddressRevisionRecord(revNum, revTypeName(revType), changedAt, changedBy, state);
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
            long revisionNumber,
            String revisionType,
            LocalDateTime changedAt,
            String changedBy,
            Map<String, Object> state
    ) {}

    public record AddressRevisionRecord(
            long revisionNumber,
            String revisionType,
            LocalDateTime changedAt,
            String changedBy,
            Map<String, Object> state
    ) {}
}
