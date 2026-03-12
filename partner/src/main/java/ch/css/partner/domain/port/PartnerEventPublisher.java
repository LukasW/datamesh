package ch.css.partner.domain.port;

/**
 * Output Port: Event Publisher Interface
 * Domain events are published through this port.
 */
public interface PartnerEventPublisher {

    /**
     * Publish partner created event
     */
    void publishPartnerCreated(String partnerId, String name, String partnerType);

    /**
     * Publish partner updated event
     */
    void publishPartnerUpdated(String partnerId, String name);

    /**
     * Publish partner deleted event
     */
    void publishPartnerDeleted(String partnerId);
}
