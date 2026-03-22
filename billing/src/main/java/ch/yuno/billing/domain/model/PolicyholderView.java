package ch.yuno.billing.domain.model;

/**
 * Read model: local materialization of Partner domain data.
 * Built from person.v1.state events (Event-Carried State Transfer).
 * No framework dependencies.
 */
public record PolicyholderView(String partnerId, String name) {

    public PolicyholderView {
        if (partnerId == null || partnerId.isBlank()) throw new IllegalArgumentException("partnerId is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    }
}
