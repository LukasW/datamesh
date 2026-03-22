package ch.yuno.policy.domain.model;

/**
 * Read Model: PartnerView – local materialization of Partner/Person data.
 * Populated by consuming person.v1.created and person.v1.updated Kafka events.
 * Policy domain is consumer only – never writes to partner data.
 */
public record PartnerView(String partnerId, String name, String insuredNumber) {

    public PartnerView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        // insuredNumber is nullable – null until first policy activation
    }

    /** Backward-compatible constructor for existing callers. */
    public PartnerView(String partnerId, String name) {
        this(partnerId, name, null);
    }

    public boolean isInsured() { return insuredNumber != null; }

    public String getPartnerId() { return partnerId; }
    public String getName() { return name; }
    public String getInsuredNumber() { return insuredNumber; }
}
