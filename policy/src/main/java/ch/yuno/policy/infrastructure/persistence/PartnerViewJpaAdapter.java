package ch.yuno.policy.infrastructure.persistence;

import ch.yuno.policy.domain.model.PartnerView;
import ch.yuno.policy.domain.port.out.PartnerViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PartnerViewJpaAdapter implements PartnerViewRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void upsert(PartnerView partner) {
        PartnerViewEntity entity = em.find(PartnerViewEntity.class, partner.getPartnerId());
        if (entity == null) {
            entity = new PartnerViewEntity();
            entity.setPartnerId(partner.getPartnerId());
        }
        entity.setName(partner.getName());
        em.merge(entity);
    }

    @Override
    public Optional<PartnerView> findById(String partnerId) {
        PartnerViewEntity entity = em.find(PartnerViewEntity.class, partnerId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<PartnerView> findAll() {
        return em.createQuery("SELECT p FROM PartnerViewEntity p ORDER BY p.name", PartnerViewEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public List<PartnerView> search(String nameQuery) {
        if (nameQuery == null || nameQuery.isBlank()) {
            return em.createQuery(
                    "SELECT p FROM PartnerViewEntity p ORDER BY p.name", PartnerViewEntity.class)
                    .setMaxResults(20)
                    .getResultList().stream().map(this::toDomain).toList();
        }
        return em.createQuery(
                "SELECT p FROM PartnerViewEntity p WHERE LOWER(p.name) LIKE :q ORDER BY p.name",
                PartnerViewEntity.class)
                .setParameter("q", "%" + nameQuery.toLowerCase() + "%")
                .setMaxResults(20)
                .getResultList().stream().map(this::toDomain).toList();
    }

    private PartnerView toDomain(PartnerViewEntity e) {
        return new PartnerView(e.getPartnerId(), e.getName());
    }
}
