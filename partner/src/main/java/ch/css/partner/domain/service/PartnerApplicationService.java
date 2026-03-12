package ch.css.partner.domain.service;

import jakarta.transaction.Transactional;
import ch.css.partner.domain.model.Partner;
import ch.css.partner.domain.port.PartnerRepository;
import ch.css.partner.domain.port.PartnerEventPublisher;
import ch.css.partner.domain.port.SearchPartnerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Application Service
 * Implements use cases for Partner domain.
 * This service is framework-agnostic and focuses on business logic.
 */
@ApplicationScoped
public class PartnerApplicationService implements SearchPartnerPort {

    private final PartnerRepository partnerRepository;
    private final PartnerEventPublisher eventPublisher;

    @Inject
    public PartnerApplicationService(PartnerRepository partnerRepository,
            PartnerEventPublisher eventPublisher) {
        this.partnerRepository = partnerRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Use Case: Search Partners by Name
     */
    @Override
    public List<Partner> searchByName(String nameFragment) {
        if (nameFragment == null || nameFragment.trim().isEmpty()) {
            return partnerRepository.findAll();
        }
        return partnerRepository.findByNameContaining(nameFragment.trim());
    }

    /**
     * Use Case: Create Partner
     */
    @Transactional
    public String createPartner(String name, String email, String phone, String partnerType) {
        // Validate
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Partner name is required");
        }

        // Create aggregate
        Partner partner = new Partner(name, email, phone,
                Enum.valueOf(ch.css.partner.domain.model.PartnerType.class, partnerType));

        // Persist
        partnerRepository.save(partner);

        // Publish event
        eventPublisher.publishPartnerCreated(partner.getPartnerId(),
                partner.getName(), partnerType);

        return partner.getPartnerId();
    }

    /**
     * Use Case: Get Partner by ID
     */
    public Partner getPartner(String partnerId) {
        return partnerRepository.findById(partnerId)
                .orElseThrow(() -> new PartnerNotFoundException("Partner not found: " + partnerId));
    }
}
