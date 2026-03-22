package ch.yuno.partner.infrastructure.persistence;

import ch.yuno.partner.domain.model.InsuredNumber;
import ch.yuno.partner.domain.port.out.InsuredNumberGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Driven adapter: generates unique insured numbers from a PostgreSQL sequence.
 * The sequence insured_number_seq is created by Flyway migration V9.
 */
@ApplicationScoped
public class InsuredNumberSequenceAdapter implements InsuredNumberGenerator {

    @Inject
    EntityManager em;

    @Override
    public InsuredNumber nextInsuredNumber() {
        Long seq = (Long) em.createNativeQuery("SELECT nextval('insured_number_seq')")
                .getSingleResult();
        return InsuredNumber.fromSequence(seq);
    }
}

