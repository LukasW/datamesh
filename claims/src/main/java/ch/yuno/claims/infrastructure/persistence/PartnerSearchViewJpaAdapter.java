package ch.yuno.claims.infrastructure.persistence;

import ch.yuno.claims.domain.model.PartnerSearchView;
import ch.yuno.claims.domain.port.out.PartnerSearchViewRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PartnerSearchViewJpaAdapter implements PartnerSearchViewRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public void upsert(PartnerSearchView view) {
        PartnerSearchViewEntity entity = em.find(PartnerSearchViewEntity.class, view.partnerId());
        if (entity == null) {
            entity = new PartnerSearchViewEntity();
            entity.setPartnerId(view.partnerId());
        }
        entity.setLastName(view.lastName());
        entity.setFirstName(view.firstName());
        entity.setDateOfBirth(view.dateOfBirth());
        entity.setSocialSecurityNumber(view.socialSecurityNumber());
        entity.setInsuredNumber(view.insuredNumber());
        em.merge(entity);
    }

    @Override
    @Transactional
    public void deleteByPartnerId(String partnerId) {
        em.createQuery("DELETE FROM PartnerSearchViewEntity e WHERE e.partnerId = :id")
                .setParameter("id", partnerId)
                .executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PartnerSearchView> searchByName(String query, int maxResults) {
        // Native SQL to leverage pg_trgm GIN index
        String term = "%" + query + "%";
        List<PartnerSearchViewEntity> results = em.createNativeQuery(
                        """
                        SELECT * FROM partner_search_view
                        WHERE (last_name || ' ' || first_name) ILIKE :term
                           OR (first_name || ' ' || last_name) ILIKE :term
                        ORDER BY last_name, first_name
                        LIMIT :maxResults
                        """, PartnerSearchViewEntity.class)
                .setParameter("term", term)
                .setParameter("maxResults", maxResults)
                .getResultList();
        return results.stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<PartnerSearchView> findBySocialSecurityNumber(String ssn) {
        List<PartnerSearchViewEntity> results = em.createQuery(
                        "SELECT e FROM PartnerSearchViewEntity e WHERE e.socialSecurityNumber = :ssn",
                        PartnerSearchViewEntity.class)
                .setParameter("ssn", ssn)
                .setMaxResults(1)
                .getResultList();
        return results.stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<PartnerSearchView> findByInsuredNumber(String insuredNumber) {
        List<PartnerSearchViewEntity> results = em.createQuery(
                        "SELECT e FROM PartnerSearchViewEntity e WHERE e.insuredNumber = :vn",
                        PartnerSearchViewEntity.class)
                .setParameter("vn", insuredNumber)
                .setMaxResults(1)
                .getResultList();
        return results.stream().findFirst().map(this::toDomain);
    }

    @Override
    public Optional<PartnerSearchView> findByPartnerId(String partnerId) {
        PartnerSearchViewEntity entity = em.find(PartnerSearchViewEntity.class, partnerId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    private PartnerSearchView toDomain(PartnerSearchViewEntity e) {
        return new PartnerSearchView(
                e.getPartnerId(),
                e.getLastName(),
                e.getFirstName(),
                e.getDateOfBirth(),
                e.getSocialSecurityNumber(),
                e.getInsuredNumber()
        );
    }
}

