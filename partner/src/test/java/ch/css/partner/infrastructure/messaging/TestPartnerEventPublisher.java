package ch.css.partner.infrastructure.messaging;

import ch.css.partner.domain.port.PartnerEventPublisher;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test bean replacing Kafka publishing with no-op behavior.
 */
@Mock
@ApplicationScoped
public class TestPartnerEventPublisher implements PartnerEventPublisher {

    @Override
    public void publishPartnerCreated(String partnerId, String name, String partnerType) {
        // no-op in tests
    }

    @Override
    public void publishPartnerUpdated(String partnerId, String name) {
        // no-op in tests
    }

    @Override
    public void publishPartnerDeleted(String partnerId) {
        // no-op in tests
    }
}
