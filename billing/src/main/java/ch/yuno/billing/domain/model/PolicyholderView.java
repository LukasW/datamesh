package ch.yuno.billing.domain.model;

/**
 * Read model: local materialization of Partner domain data.
 * Built from person.v1.state events (Event-Carried State Transfer).
 * No framework dependencies.
 */
public record PolicyholderView(String partnerId, String name, String insuredNumber) {

    public PolicyholderView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        // insuredNumber nullable
    }

    /** Backward-compatible constructor. */
    public PolicyholderView(String partnerId, String name) {
        this(partnerId, name, null);
    }

    public boolean isInsured() { return insuredNumber != null; }
}
