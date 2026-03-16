package ch.css.policy.domain.model;

/**
 * Read Model: PartnerView – local materialization of Partner/Person data.
 * Populated by consuming person.v1.created and person.v1.updated Kafka events.
 * Policy domain is consumer only – never writes to partner data.
 */
public record PartnerView(String partnerId, String name) {

    public PartnerView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    }

    public String getPartnerId() { return partnerId; }
    public String getName() { return name; }
}
