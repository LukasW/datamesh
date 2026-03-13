package ch.css.policy.domain.model;
import java.math.BigDecimal;
/**
 * Read Model: ProduktSicht – local materialization of Product data.
 * Populated by consuming product.v1.defined, product.v1.updated and product.v1.deprecated events.
 */
public record ProduktSicht(
        String produktId,
        String name,
        String productLine,
        BigDecimal basePremium,
        boolean active) {
    public String getProduktId() { return produktId; }
    public String getName() { return name; }
    public String getProductLine() { return productLine; }
    public BigDecimal getBasePremium() { return basePremium; }
    public boolean isActive() { return active; }
    public String getDisplayLabel() {
        return name + " (" + productLine + ")";
    }
}
