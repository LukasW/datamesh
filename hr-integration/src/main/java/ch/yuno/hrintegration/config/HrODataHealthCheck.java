package ch.yuno.hrintegration.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Readiness check that probes the HR OData endpoint.
 */
@Readiness
@ApplicationScoped
public class HrODataHealthCheck implements HealthCheck {

    @ConfigProperty(name = "hr.odata.base-url")
    String odataBaseUrl;

    @Override
    public HealthCheckResponse call() {
        try {
            var response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(odataBaseUrl + "/Employees"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                    HttpResponse.BodyHandlers.ofString());

            return HealthCheckResponse.named("hr-odata")
                .status(response.statusCode() == 200)
                .withData("url", odataBaseUrl)
                .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("hr-odata")
                .down()
                .withData("error", e.getMessage())
                .build();
        }
    }
}
