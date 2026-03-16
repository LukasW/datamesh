package ch.css.policy.infrastructure.persistence;

import ch.css.policy.domain.model.ProductView;
import ch.css.policy.domain.port.out.ProductViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProductViewJpaAdapter implements ProductViewRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void upsert(ProductView product) {
        ProductViewEntity entity = em.find(ProductViewEntity.class, product.getProductId());
        if (entity == null) {
            entity = new ProductViewEntity();
            entity.setProductId(product.getProductId());
        }
        entity.setName(product.getName());
        entity.setProductLine(product.getProductLine());
        entity.setBasePremium(product.getBasePremium());
        entity.setActive(product.isActive());
        em.merge(entity);
    }

    @Override
    @Transactional
    public void deactivate(String productId) {
        ProductViewEntity entity = em.find(ProductViewEntity.class, productId);
        if (entity != null) {
            entity.setActive(false);
            em.merge(entity);
        }
    }

    @Override
    public Optional<ProductView> findById(String productId) {
        ProductViewEntity entity = em.find(ProductViewEntity.class, productId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<ProductView> findAllActive() {
        return em.createQuery(
                "SELECT p FROM ProductViewEntity p WHERE p.active = true ORDER BY p.name",
                ProductViewEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public List<ProductView> findAll() {
        return em.createQuery("SELECT p FROM ProductViewEntity p ORDER BY p.name",
                ProductViewEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    private ProductView toDomain(ProductViewEntity e) {
        return new ProductView(e.getProductId(), e.getName(),
                e.getProductLine(), e.getBasePremium(), e.isActive());
    }
}
