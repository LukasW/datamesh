package ch.css.partner.domain.model;

/**
 * Enumeration of partner types in the insurance domain
 */
public enum PartnerType {
    CUSTOMER("Versicherungsnehmer"),
    BROKER("Makler"),
    AGENT("Agent"),
    SUPPLIER("Lieferant");

    private final String description;

    PartnerType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
