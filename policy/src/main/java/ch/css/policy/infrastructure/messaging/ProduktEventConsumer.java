package ch.css.policy.infrastructure.messaging;

import ch.css.policy.domain.model.ProduktSicht;
import ch.css.policy.domain.port.out.ProduktSichtRepository;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * Kafka consumer for Product domain events.
 * Materializes the local ProduktSicht read model from product.v1.defined,
 * product.v1.updated and product.v1.deprecated events (ADR-001).
 */
@ApplicationScoped
public class ProduktEventConsumer {

    private static final Logger log = Logger.getLogger(ProduktEventConsumer.class);

    @Inject
    ProduktSichtRepository produktSichtRepository;

    @Incoming("product-defined-in")
    @Transactional
    public void onProductDefined(String json) {
        upsertProduktFromEvent(json);
    }

    @Incoming("product-updated-in")
    @Transactional
    public void onProductUpdated(String json) {
        upsertProduktFromEvent(json);
    }

    @Incoming("product-deprecated-in")
    @Transactional
    public void onProductDeprecated(String json) {
        try {
            JsonObject event  = new JsonObject(json);
            String produktId  = event.getString("productId");
            if (produktId == null) {
                log.warnf("ProductDeprecated missing productId: %s", json);
                return;
            }
            produktSichtRepository.deactivate(produktId);
            log.debugf("ProduktSicht deactivated: %s", produktId);
        } catch (Exception e) {
            log.errorf("Failed to process ProductDeprecated event: %s | %s", e.getMessage(), json);
        }
    }

    private void upsertProduktFromEvent(String json) {
        try {
            JsonObject event    = new JsonObject(json);
            String produktId    = event.getString("productId");
            String name         = event.getString("name");
            String productLine  = event.getString("productLine", "UNBEKANNT");
            BigDecimal premium  = event.containsKey("basePremium")
                    ? new BigDecimal(event.getValue("basePremium").toString())
                    : BigDecimal.ZERO;
            if (produktId == null || name == null) {
                log.warnf("Product event missing required fields: %s", json);
                return;
            }
            produktSichtRepository.upsert(new ProduktSicht(produktId, name, productLine, premium, true));
            log.debugf("ProduktSicht upserted: %s -> %s (%s)", produktId, name, productLine);
        } catch (Exception e) {
            log.errorf("Failed to process product event: %s | %s", e.getMessage(), json);
        }
    }
}
