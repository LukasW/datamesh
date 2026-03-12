package ch.css.partner.infrastructure.persistence;

import ch.css.partner.domain.model.Partner;
import ch.css.partner.domain.model.PartnerType;
import ch.css.partner.domain.model.PartnerStatus;
import ch.css.partner.domain.port.PartnerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;

/**
 * Persistence Adapter: PostgreSQL/JPA Implementation
 */
@ApplicationScoped
public class PartnerJpaAdapter implements PartnerRepository {

    @Inject
    EntityManager em;

    @Override
    public void save(Partner partner) {
        PartnerEntity entity = new PartnerEntity();
        entity.partnerId = partner.getPartnerId();
        entity.name = partner.getName();
        entity.email = partner.getEmail();
        entity.phone = partner.getPhone();
        entity.street = partner.getStreet();
        entity.city = partner.getCity();
        entity.postalCode = partner.getPostalCode();
        entity.country = partner.getCountry();
        entity.partnerType = partner.getPartnerType().name();
        entity.status = partner.getStatus().name();

        PartnerEntity existing = em.find(PartnerEntity.class, partner.getPartnerId());
        if (existing != null) {
            em.merge(entity);
        } else {
            em.persist(entity);
        }
    }

    @Override
    public Optional<Partner> findById(String partnerId) {
        PartnerEntity entity = em.find(PartnerEntity.class, partnerId);
        return Optional.ofNullable(entity)
                .map(this::toDomain);
    }

    @Override
    public List<Partner> findByNameContaining(String nameFragment) {
        return em.createQuery(
                "SELECT p FROM PartnerEntity p WHERE p.name LIKE :name",
                PartnerEntity.class)
                .setParameter("name", "%" + nameFragment + "%")
                .getResultList()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Partner> findByEmail(String email) {
        List<PartnerEntity> results = em.createQuery(
                "SELECT p FROM PartnerEntity p WHERE p.email = :email",
                PartnerEntity.class)
                .setParameter("email", email)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<Partner> findAll() {
        return em.createQuery("SELECT p FROM PartnerEntity p", PartnerEntity.class)
                .getResultList()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void delete(String partnerId) {
        PartnerEntity entity = em.find(PartnerEntity.class, partnerId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    private Partner toDomain(PartnerEntity entity) {
        Partner partner = new Partner(entity.name, entity.email, entity.phone,
                PartnerType.valueOf(entity.partnerType));
        // Use reflection to set immutable fields
        try {
            var partnerIdField = Partner.class.getDeclaredField("partnerId");
            partnerIdField.setAccessible(true);
            partnerIdField.set(partner, entity.partnerId);

            var statusField = Partner.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(partner, PartnerStatus.valueOf(entity.status));

            var streetField = Partner.class.getDeclaredField("street");
            streetField.setAccessible(true);
            streetField.set(partner, entity.street);

            var cityField = Partner.class.getDeclaredField("city");
            cityField.setAccessible(true);
            cityField.set(partner, entity.city);

            var postalCodeField = Partner.class.getDeclaredField("postalCode");
            postalCodeField.setAccessible(true);
            postalCodeField.set(partner, entity.postalCode);

            var countryField = Partner.class.getDeclaredField("country");
            countryField.setAccessible(true);
            countryField.set(partner, entity.country);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to map PartnerEntity to Partner", e);
        }
        return partner;
    }
}
