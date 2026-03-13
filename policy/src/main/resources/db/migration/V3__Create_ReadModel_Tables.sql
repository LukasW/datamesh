-- Partner read model (materialized from person.v1.created + person.v1.updated)
-- partnerId corresponds to personId from the Partner service.
CREATE TABLE partner_sicht (
  partner_id   VARCHAR(36)  PRIMARY KEY,
  name         VARCHAR(255) NOT NULL,
  upserted_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Product read model (materialized from product.v1.defined + product.v1.updated + product.v1.deprecated)
CREATE TABLE produkt_sicht (
  produkt_id   VARCHAR(36)   PRIMARY KEY,
  name         VARCHAR(255)  NOT NULL,
  product_line VARCHAR(50)   NOT NULL,
  base_premium NUMERIC(12,2) NOT NULL,
  active       BOOLEAN       NOT NULL DEFAULT TRUE,
  upserted_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_produkt_sicht_active ON produkt_sicht(active);

