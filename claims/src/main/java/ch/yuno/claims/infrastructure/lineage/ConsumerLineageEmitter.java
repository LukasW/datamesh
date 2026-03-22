package ch.yuno.claims.infrastructure.lineage;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineageClient;
import io.openlineage.client.transports.HttpConfig;
import io.openlineage.client.transports.HttpTransport;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Emits OpenLineage events on startup to register Kafka consumer lineage in Marquez.
 * Declares which topics claims-service consumes and which local tables it materializes.
 */
@ApplicationScoped
public class ConsumerLineageEmitter {

    private static final Logger LOG = Logger.getLogger(ConsumerLineageEmitter.class);
    private static final String NAMESPACE = "quarkus-services";
    private static final URI PRODUCER = URI.create("https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/spec/OpenLineage.json");

    @ConfigProperty(name = "openlineage.url", defaultValue = "http://localhost:5050")
    String openLineageUrl;

    void onStart(@Observes StartupEvent event) {
        try {
            var config = new HttpConfig();
            config.setUrl(URI.create(openLineageUrl));
            var transport = new HttpTransport(config);
            var client = new OpenLineageClient(transport);
            var ol = new OpenLineage(PRODUCER);

            var input = ol.newInputDataset("kafka", "policy.v1.issued", null, null);
            var output = ol.newOutputDataset("postgresql", "claims_db.public.policy_snapshot", null, null);
            var run = ol.newRun(UUID.randomUUID(), null);
            var job = ol.newJob(NAMESPACE, "claims-policy-issued-consumer", null);
            var runEvent = ol.newRunEvent(
                    ZonedDateTime.now(ZoneOffset.UTC),
                    OpenLineage.RunEvent.EventType.COMPLETE,
                    run, job, List.of(input), List.of(output));
            client.emit(runEvent);

            LOG.info("OpenLineage consumer lineage events emitted to " + openLineageUrl);
        } catch (Exception e) {
            LOG.warn("Failed to emit OpenLineage consumer lineage (non-blocking): " + e.getMessage());
        }
    }
}
