package ch.yuno.product.infrastructure.lineage;

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
 * endpoint as a lineage job in Marquez. Declares that product-service reads from
 * its local product table to serve premium calculation requests.
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

            var input = ol.newInputDataset("postgresql", "product_db.public.product", null, null);
            var output = ol.newOutputDataset("grpc", "product-service/PremiumCalculation", null, null);
            var run = ol.newRun(UUID.randomUUID(), null);
            var job = ol.newJob(NAMESPACE, "product-premium-calculation-server", null);
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
