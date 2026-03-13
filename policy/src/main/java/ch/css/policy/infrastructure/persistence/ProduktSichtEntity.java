package ch.css.policy.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "produkt_sicht")
public class ProduktSichtEntity {

    @Id
    @Column(name = "produkt_id", length = 36)
    private String produktId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "product_line", nullable = false, length = 50)
    private String productLine;

    @Column(name = "base_premium", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePremium;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "upserted_at", nullable = false)
    private LocalDateTime upsertedAt;

    @PrePersist
    @PreUpdate
    protected void onUpsert() { upsertedAt = LocalDateTime.now(); }

    public String getProduktId() { return produktId; }
    public void setProduktId(String produktId) { this.produktId = produktId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProductLine() { return productLine; }
    public void setProductLine(String productLine) { this.productLine = productLine; }
    public BigDecimal getBasePremium() { return basePremium; }
    public void setBasePremium(BigDecimal basePremium) { this.basePremium = basePremium; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getUpsertedAt() { return upsertedAt; }
}
