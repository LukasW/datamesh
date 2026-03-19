package ch.css.policy.infrastructure.persistence;

import ch.css.policy.domain.port.out.PolicyNumberPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;

/**
 * Generates unique policy numbers using a database sequence.
 * Format: POL-{year}-{sequence} (e.g. POL-2026-0001).
 */
@ApplicationScoped
public class PolicyNumberGenerator implements PolicyNumberPort {

    @Inject
    EntityManager em;

    @Override
    public String nextPolicyNumber() {
        int year = LocalDate.now().getYear();
        Long seq = (Long) em.createNativeQuery("SELECT nextval('policy_number_seq')")
                .getSingleResult();
        return "POL-%d-%04d".formatted(year, seq);
    }
}
