package ch.yuno.product.infrastructure.persistence;

import ch.yuno.product.domain.model.PageRequest;
import ch.yuno.product.domain.model.PageResult;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.model.ProductLine;
import ch.yuno.product.domain.model.ProductStatus;
import ch.yuno.product.domain.port.out.ProductRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProductJpaAdapter implements ProductRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public Product save(Product product) {
        ProductEntity entity = em.find(ProductEntity.class, product.getProductId().value());
        if (entity == null) {
            entity = toEntity(product);
            em.persist(entity);
        } else {
            updateEntity(entity, product);
        }
        return product;
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        ProductEntity entity = em.find(ProductEntity.class, productId.value());
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return em.createQuery("SELECT p FROM ProductEntity p ORDER BY p.name", ProductEntity.class)
                .getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public List<Product> search(String name, ProductLine productLine) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM ProductEntity p WHERE 1=1");
        if (name != null && !name.isBlank()) jpql.append(" AND LOWER(p.name) LIKE LOWER(:name)");
        if (productLine != null) jpql.append(" AND p.productLine = :productLine");
        jpql.append(" ORDER BY p.name");

        TypedQuery<ProductEntity> query = em.createQuery(jpql.toString(), ProductEntity.class);
        if (name != null && !name.isBlank()) query.setParameter("name", "%" + name + "%");
        if (productLine != null) query.setParameter("productLine", productLine.name());

        return query.getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<Product> search(String name, ProductLine productLine, PageRequest pageRequest) {
        String whereClause = buildProductWhereClause(name, productLine);

        StringBuilder countJpql = new StringBuilder("SELECT COUNT(p) FROM ProductEntity p WHERE 1=1");
        countJpql.append(whereClause);
        TypedQuery<Long> countQuery = em.createQuery(countJpql.toString(), Long.class);
        setProductParameters(countQuery, name, productLine);
        long totalElements = countQuery.getSingleResult();

        StringBuilder jpql = new StringBuilder("SELECT p FROM ProductEntity p WHERE 1=1");
        jpql.append(whereClause);
        jpql.append(" ORDER BY p.name");
        TypedQuery<ProductEntity> query = em.createQuery(jpql.toString(), ProductEntity.class);
        setProductParameters(query, name, productLine);
        query.setFirstResult(pageRequest.page() * pageRequest.size());
        query.setMaxResults(pageRequest.size());

        List<Product> content = query.getResultList().stream().map(this::toDomain).toList();
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.size());
        return new PageResult<>(content, totalElements, totalPages);
    }

    private String buildProductWhereClause(String name, ProductLine productLine) {
        StringBuilder clause = new StringBuilder();
        if (name != null && !name.isBlank()) clause.append(" AND LOWER(p.name) LIKE LOWER(:name)");
        if (productLine != null) clause.append(" AND p.productLine = :productLine");
        return clause.toString();
    }

    private <T> void setProductParameters(TypedQuery<T> query, String name, ProductLine productLine) {
        if (name != null && !name.isBlank()) query.setParameter("name", "%" + name + "%");
        if (productLine != null) query.setParameter("productLine", productLine.name());
    }

    @Override
    @Transactional
    public void delete(ProductId productId) {
        ProductEntity entity = em.find(ProductEntity.class, productId.value());
        if (entity != null) {
            em.remove(entity);
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ProductEntity toEntity(Product product) {
        ProductEntity e = new ProductEntity();
        e.setProductId(product.getProductId().value());
        updateEntity(e, product);
        return e;
    }

    private void updateEntity(ProductEntity e, Product product) {
        e.setName(product.getName());
        e.setDescription(product.getDescription());
        e.setProductLine(product.getProductLine().name());
        e.setBasePremium(product.getBasePremium());
        e.setStatus(product.getStatus().name());
    }

    private Product toDomain(ProductEntity e) {
        return new Product(
                ProductId.of(e.getProductId()),
                e.getName(),
                e.getDescription(),
                ProductLine.valueOf(e.getProductLine()),
                e.getBasePremium(),
                ProductStatus.valueOf(e.getStatus())
        );
    }
}
