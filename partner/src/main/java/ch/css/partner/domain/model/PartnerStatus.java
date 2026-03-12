package ch.css.partner.domain.model;

/**
 * Enumeration of partner status
 */
public enum PartnerStatus {
    ACTIVE("Aktiv"),
    INACTIVE("Inaktiv"),
    BLOCKED("Gesperrt");

    private final String description;

    PartnerStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
