package ch.yuno.policy.infrastructure.lineage;

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
 * Declares which topics policy-service consumes and which local tables it materializes.
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

            emitConsumerLineage(client, ol, "policy-partner-person-created-consumer",
                    "person.v1.created", "policy_db.public.partner_snapshot");
            emitConsumerLineage(client, ol, "policy-partner-person-updated-consumer",
                    "person.v1.updated", "policy_db.public.partner_snapshot");
            emitConsumerLineage(client, ol, "policy-product-defined-consumer",
                    "product.v1.defined", "policy_db.public.product_snapshot");
            emitConsumerLineage(client, ol, "policy-product-updated-consumer",
                    "product.v1.updated", "policy_db.public.product_snapshot");
            emitConsumerLineage(client, ol, "policy-product-deprecated-consumer",
                    "product.v1.deprecated", "policy_db.public.product_snapshot");

            LOG.info("OpenLineage consumer lineage events emitted to " + openLineageUrl);
        } catch (Exception e) {
            LOG.warn("Failed to emit OpenLineage consumer lineage (non-blocking): " + e.getMessage());
        }
    }

    private void emitConsumerLineage(OpenLineageClient client, OpenLineage ol,
                                     String jobName, String inputTopic, String outputTable) {
        var input = ol.newInputDataset("kafka", inputTopic, null, null);
        var output = ol.newOutputDataset("postgresql", outputTable, null, null);
        var run = ol.newRun(UUID.randomUUID(), null);
        var job = ol.newJob(NAMESPACE, jobName, null);
        var runEvent = ol.newRunEvent(
                ZonedDateTime.now(ZoneOffset.UTC),
                OpenLineage.RunEvent.EventType.COMPLETE,
                run, job, List.of(input), List.of(output));
        client.emit(runEvent);
    }
}
