package ch.css.partner.domain.port;

import ch.css.partner.domain.model.Partner;
import java.util.List;

/**
 * Input Port: Use Cases / Application Service Interface
 */
public interface SearchPartnerPort {

    /**
     * Search partners by name fragment
     */
    List<Partner> searchByName(String nameFragment);
}
