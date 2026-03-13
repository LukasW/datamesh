package ch.css.policy.infrastructure.persistence;

import ch.css.policy.domain.model.Deckung;
import ch.css.policy.domain.model.Deckungstyp;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.model.PolicyStatus;
import ch.css.policy.domain.port.out.PolicyRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PolicyJpaAdapter implements PolicyRepository {

    @Inject
    EntityManager em;

    @Override
    @Transactional
    public Policy save(Policy policy) {
        PolicyEntity entity = em.find(PolicyEntity.class, policy.getPolicyId());
        if (entity == null) {
            entity = toEntity(policy);
            em.persist(entity);
        } else {
            updateEntity(entity, policy);
        }
        return policy;
    }

    @Override
    public Optional<Policy> findById(String policyId) {
        PolicyEntity entity = em.find(PolicyEntity.class, policyId);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public Optional<Policy> findByPolicyNummer(String policyNummer) {
        List<PolicyEntity> results = em.createQuery(
                "SELECT p FROM PolicyEntity p WHERE p.policyNummer = :nr", PolicyEntity.class)
                .setParameter("nr", policyNummer)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
    }

    @Override
    public List<Policy> search(String policyNummer, String partnerId, PolicyStatus status) {
        StringBuilder jpql = new StringBuilder("SELECT p FROM PolicyEntity p WHERE 1=1");
        if (policyNummer != null && !policyNummer.isBlank())
            jpql.append(" AND LOWER(p.policyNummer) LIKE LOWER(:policyNummer)");
        if (partnerId != null && !partnerId.isBlank())
            jpql.append(" AND p.partnerId = :partnerId");
        if (status != null)
            jpql.append(" AND p.status = :status");
        jpql.append(" ORDER BY p.createdAt DESC");

        TypedQuery<PolicyEntity> query = em.createQuery(jpql.toString(), PolicyEntity.class);
        if (policyNummer != null && !policyNummer.isBlank())
            query.setParameter("policyNummer", "%" + policyNummer + "%");
        if (partnerId != null && !partnerId.isBlank())
            query.setParameter("partnerId", partnerId);
        if (status != null)
            query.setParameter("status", status.name());

        return query.getResultList().stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void delete(String policyId) {
        PolicyEntity entity = em.find(PolicyEntity.class, policyId);
        if (entity != null) {
            em.remove(entity);
        }
    }

    @Override
    public boolean existsByPolicyNummer(String policyNummer) {
        Long count = em.createQuery(
                "SELECT COUNT(p) FROM PolicyEntity p WHERE p.policyNummer = :nr", Long.class)
                .setParameter("nr", policyNummer)
                .getSingleResult();
        return count > 0;
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PolicyEntity toEntity(Policy policy) {
        PolicyEntity e = new PolicyEntity();
        e.setPolicyId(policy.getPolicyId());
        e.setPolicyNummer(policy.getPolicyNummer());
        e.setPartnerId(policy.getPartnerId());
        e.setProduktId(policy.getProduktId());
        e.setStatus(policy.getStatus().name());
        e.setVersicherungsbeginn(policy.getVersicherungsbeginn());
        e.setVersicherungsende(policy.getVersicherungsende());
        e.setPraemie(policy.getPraemie());
        e.setSelbstbehalt(policy.getSelbstbehalt());
        for (Deckung d : policy.getDeckungen()) {
            e.getDeckungen().add(toDeckungEntity(d, e));
        }
        return e;
    }

    private void updateEntity(PolicyEntity e, Policy policy) {
        e.setProduktId(policy.getProduktId());
        e.setStatus(policy.getStatus().name());
        e.setVersicherungsbeginn(policy.getVersicherungsbeginn());
        e.setVersicherungsende(policy.getVersicherungsende());
        e.setPraemie(policy.getPraemie());
        e.setSelbstbehalt(policy.getSelbstbehalt());

        // sync deckungen: remove deleted, update existing, add new
        e.getDeckungen().removeIf(de ->
                policy.getDeckungen().stream().noneMatch(d -> d.getDeckungId().equals(de.getDeckungId())));
        for (Deckung d : policy.getDeckungen()) {
            DeckungEntity existing = e.getDeckungen().stream()
                    .filter(de -> de.getDeckungId().equals(d.getDeckungId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                existing.setVersicherungssumme(d.getVersicherungssumme());
            } else {
                e.getDeckungen().add(toDeckungEntity(d, e));
            }
        }
    }

    private DeckungEntity toDeckungEntity(Deckung d, PolicyEntity policyEntity) {
        DeckungEntity de = new DeckungEntity();
        de.setDeckungId(d.getDeckungId());
        de.setPolicy(policyEntity);
        de.setDeckungstyp(d.getDeckungstyp().name());
        de.setVersicherungssumme(d.getVersicherungssumme());
        return de;
    }

    private Policy toDomain(PolicyEntity e) {
        Policy policy = new Policy(
                e.getPolicyId(),
                e.getPolicyNummer(),
                e.getPartnerId(),
                e.getProduktId(),
                PolicyStatus.valueOf(e.getStatus()),
                e.getVersicherungsbeginn(),
                e.getVersicherungsende(),
                e.getPraemie(),
                e.getSelbstbehalt()
        );
        List<Deckung> deckungen = new ArrayList<>();
        for (DeckungEntity de : e.getDeckungen()) {
            deckungen.add(new Deckung(
                    de.getDeckungId(),
                    e.getPolicyId(),
                    Deckungstyp.valueOf(de.getDeckungstyp()),
                    de.getVersicherungssumme()
            ));
        }
        policy.setDeckungen(deckungen);
        return policy;
    }
}

