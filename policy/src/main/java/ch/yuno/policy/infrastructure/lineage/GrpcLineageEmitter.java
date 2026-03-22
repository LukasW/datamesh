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
 * Emits OpenLineage events on startup to register the gRPC premium calculation
 * client call as a lineage job in Marquez. Declares that policy-service calls
 * product-service's PremiumCalculation gRPC endpoint and writes the result
 * into the local policy table.
 */
@ApplicationScoped
public class GrpcLineageEmitter {

    private static final Logger LOG = Logger.getLogger(GrpcLineageEmitter.class);
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

            var input = ol.newInputDataset("grpc", "product-service/PremiumCalculation", null, null);
            var output = ol.newOutputDataset("postgresql", "policy_db.public.policy", null, null);
            var run = ol.newRun(UUID.randomUUID(), null);
            var job = ol.newJob(NAMESPACE, "policy-premium-calculation-client", null);
            var runEvent = ol.newRunEvent(
                    ZonedDateTime.now(ZoneOffset.UTC),
                    OpenLineage.RunEvent.EventType.COMPLETE,
                    run, job, List.of(input), List.of(output));
            client.emit(runEvent);

            LOG.info("OpenLineage gRPC lineage event emitted to " + openLineageUrl);
        } catch (Exception e) {
            LOG.warn("Failed to emit OpenLineage gRPC lineage (non-blocking): " + e.getMessage());
        }
    }
}
