package ch.yuno.hrintegration.route;

import ch.yuno.hrintegration.processor.ChangeDetector;
import ch.yuno.hrintegration.processor.EmployeeEventProcessor;
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
 * Camel route that polls the HR system's OData Employees endpoint
 * and produces ECST state + change events to Kafka.
 */
@ApplicationScoped
public class EmployeeSyncRoute extends RouteBuilder {

    private static final String ENTITY_SET = "Employees";
    private static final String STATE_TOPIC = "hr.v1.employee.state";
    private static final String CHANGED_TOPIC = "hr.v1.employee.changed";

    @ConfigProperty(name = "hr.odata.base-url")
    String odataBaseUrl;

    @ConfigProperty(name = "hr.odata.polling-interval", defaultValue = "60000")
    long pollingInterval;

    @Inject
    ChangeDetector changeDetector;

    @Inject
    EmployeeEventProcessor processor;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void configure() {
        errorHandler(deadLetterChannel("kafka:hr-integration-dlq")
            .maximumRedeliveries(3)
            .redeliveryDelay(5000)
            .retryAttemptedLogLevel(LoggingLevel.WARN));

        from("timer:employee-sync?period=" + pollingInterval)
            .routeId("employee-sync")
            .log(LoggingLevel.DEBUG, "Polling HR system for employee changes...")
            .process(this::fetchAndProcess);
    }

    private void fetchAndProcess(Exchange exchange) {
        var url = changeDetector.buildUrl(odataBaseUrl, ENTITY_SET);
        try {
            var template = exchange.getContext().createProducerTemplate();
            var response = template.requestBody(url + "?bridgeEndpoint=true", null, String.class);
            if (response == null) {
                log.warn("Employee fetch returned null – skipping.");
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
                    KafkaConstants.KEY, stateEvent.employeeId());

                template.sendBodyAndHeader("kafka:" + CHANGED_TOPIC,
                    objectMapper.writeValueAsString(changedEvent),
                    KafkaConstants.KEY, changedEvent.employeeId());

                count++;
            }

            changeDetector.recordPoll(ENTITY_SET);
            log.info("Employee sync completed. {} entities processed.", count);
        } catch (Exception e) {
            log.error("Failed to sync employees from HR system: {}", e.getMessage(), e);
        }
    }
}
