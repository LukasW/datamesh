package ch.css.partner.domain.port;

import ch.css.partner.domain.model.Partner;
import java.util.List;
import java.util.Optional;

/**
 * Output Port: Repository Interface
 * Domain-agnostic interface for partner persistence.
 */
public interface PartnerRepository {

    /**
     * Save or update a partner
     */
    void save(Partner partner);

    /**
     * Find partner by ID
     */
    Optional<Partner> findById(String partnerId);

    /**
     * Find partners by name (partial match)
     */
    List<Partner> findByNameContaining(String nameFragment);

    /**
     * Find partners by email
     */
    Optional<Partner> findByEmail(String email);

    /**
     * Find all partners
     */
    List<Partner> findAll();

    /**
     * Delete a partner
     */
    void delete(String partnerId);
}
