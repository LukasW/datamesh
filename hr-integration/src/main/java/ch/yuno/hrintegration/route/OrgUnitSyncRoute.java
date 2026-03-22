package ch.yuno.hrintegration.route;

import ch.yuno.hrintegration.processor.ChangeDetector;
import ch.yuno.hrintegration.processor.OrgUnitEventProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Camel route that polls the HR system's OData OrganizationUnits endpoint
 * and produces ECST state + change events to Kafka.
 */
@ApplicationScoped
public class OrgUnitSyncRoute extends RouteBuilder {

    private static final String ENTITY_SET = "OrganizationUnits";
    private static final String STATE_TOPIC = "hr.v1.org-unit.state";
    private static final String CHANGED_TOPIC = "hr.v1.org-unit.changed";

    @ConfigProperty(name = "hr.odata.base-url")
    String odataBaseUrl;

    @ConfigProperty(name = "hr.odata.polling-interval", defaultValue = "60000")
    long pollingInterval;

    @Inject
    ChangeDetector changeDetector;

    @Inject
    OrgUnitEventProcessor processor;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("kafka:hr-integration-dlq")
            .maximumRedeliveries(3)
            .redeliveryDelay(5000)
            .retryAttemptedLogLevel(LoggingLevel.WARN));

        from("timer:orgunit-sync?period=" + pollingInterval)
            .routeId("orgunit-sync")
            .log(LoggingLevel.DEBUG, "Polling HR system for org unit changes...")
            .process(this::fetchAndProcess);
    }

    private void fetchAndProcess(Exchange exchange) {
        var url = changeDetector.buildUrl(odataBaseUrl, ENTITY_SET);
        try {
            var template = exchange.getContext().createProducerTemplate();
            var response = template.requestBody(url + "?bridgeEndpoint=true", null, String.class);
            if (response == null) {
                log.warn("OrgUnit fetch returned null – skipping.");
                return;
            }

            var root = objectMapper.readTree(response);
            var valueNode = root.get("value");
            if (valueNode == null || !valueNode.isArray()) {
                log.warn("No 'value' array in OData response – skipping.");
                return;
            }

            int count = 0;
            for (JsonNode node : valueNode) {
                var stateEvent = processor.toStateEvent(node);
                var changedEvent = processor.toChangedEvent(node);

                template.sendBodyAndHeader("kafka:" + STATE_TOPIC,
                    objectMapper.writeValueAsString(stateEvent),
                    KafkaConstants.KEY, stateEvent.orgUnitId());

                template.sendBodyAndHeader("kafka:" + CHANGED_TOPIC,
                    objectMapper.writeValueAsString(changedEvent),
                    KafkaConstants.KEY, changedEvent.orgUnitId());

                count++;
            }

            changeDetector.recordPoll(ENTITY_SET);
            log.info("OrgUnit sync completed. {} entities processed.", count);
        } catch (Exception e) {
            log.error("Failed to sync org units from HR system: {}", e.getMessage(), e);
        }
    }
}
