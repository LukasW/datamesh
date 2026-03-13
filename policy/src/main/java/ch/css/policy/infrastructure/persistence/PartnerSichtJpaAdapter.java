package ch.css.policy.infrastructure.persistence;

import ch.css.policy.domain.model.PartnerSicht;
import ch.css.policy.domain.port.out.PartnerSichtRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PartnerSichtJpaAdapter implements PartnerSichtRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void upsert(PartnerSicht partner) {
        PartnerSichtEntity entity = em.find(PartnerSichtEntity.class, partner.getPartnerId());
        if (entity == null) {
            entity = new PartnerSichtEntity();
            entity.setPartnerId(partner.getPartnerId());
        }
        entity.setName(partner.getName());
        em.merge(entity);
    }

    @Override
    public Optional<PartnerSicht> findById(String partnerId) {
        PartnerSichtEntity entity = em.find(PartnerSichtEntity.class, partnerId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<PartnerSicht> findAll() {
        return em.createQuery("SELECT p FROM PartnerSichtEntity p ORDER BY p.name", PartnerSichtEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public List<PartnerSicht> search(String nameQuery) {
        if (nameQuery == null || nameQuery.isBlank()) {
            return em.createQuery(
                    "SELECT p FROM PartnerSichtEntity p ORDER BY p.name", PartnerSichtEntity.class)
                    .setMaxResults(20)
                    .getResultList().stream().map(this::toDomain).toList();
        }
        return em.createQuery(
                "SELECT p FROM PartnerSichtEntity p WHERE LOWER(p.name) LIKE :q ORDER BY p.name",
                PartnerSichtEntity.class)
                .setParameter("q", "%" + nameQuery.toLowerCase() + "%")
                .setMaxResults(20)
                .getResultList().stream().map(this::toDomain).toList();
    }

    private PartnerSicht toDomain(PartnerSichtEntity e) {
        return new PartnerSicht(e.getPartnerId(), e.getName());
    }
}
