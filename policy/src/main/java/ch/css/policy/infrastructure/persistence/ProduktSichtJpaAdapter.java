package ch.css.policy.infrastructure.persistence;

import ch.css.policy.domain.model.ProduktSicht;
import ch.css.policy.domain.port.out.ProduktSichtRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProduktSichtJpaAdapter implements ProduktSichtRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void upsert(ProduktSicht produkt) {
        ProduktSichtEntity entity = em.find(ProduktSichtEntity.class, produkt.getProduktId());
        if (entity == null) {
            entity = new ProduktSichtEntity();
            entity.setProduktId(produkt.getProduktId());
        }
        entity.setName(produkt.getName());
        entity.setProductLine(produkt.getProductLine());
        entity.setBasePremium(produkt.getBasePremium());
        entity.setActive(produkt.isActive());
        em.merge(entity);
    }

    @Override
    @Transactional
    public void deactivate(String produktId) {
        ProduktSichtEntity entity = em.find(ProduktSichtEntity.class, produktId);
        if (entity != null) {
            entity.setActive(false);
            em.merge(entity);
        }
    }

    @Override
    public Optional<ProduktSicht> findById(String produktId) {
        ProduktSichtEntity entity = em.find(ProduktSichtEntity.class, produktId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<ProduktSicht> findAllActive() {
        return em.createQuery(
                "SELECT p FROM ProduktSichtEntity p WHERE p.active = true ORDER BY p.name",
                ProduktSichtEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public List<ProduktSicht> findAll() {
        return em.createQuery("SELECT p FROM ProduktSichtEntity p ORDER BY p.name",
                ProduktSichtEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    private ProduktSicht toDomain(ProduktSichtEntity e) {
        return new ProduktSicht(e.getProduktId(), e.getName(),
                e.getProductLine(), e.getBasePremium(), e.isActive());
    }
}
