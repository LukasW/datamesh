package ch.css.claims.infrastructure.api;

import ch.css.claims.domain.port.out.CoverageCheckPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * REST client adapter for checking policy coverage via the Policy service.
 * Uses SmallRye Fault Tolerance circuit breaker for resilience (ADR-003).
 */
@ApplicationScoped
public class PolicyCoverageRestClient implements CoverageCheckPort {

    private static final Logger LOG = Logger.getLogger(PolicyCoverageRestClient.class);

    private final PolicyServiceClient policyServiceClient;

    public PolicyCoverageRestClient(@RestClient PolicyServiceClient policyServiceClient) {
        this.policyServiceClient = policyServiceClient;
    }

    @Override
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 5000)
    @Fallback(fallbackMethod = "coverageCheckFallback")
    public boolean checkCoverage(String policyId) {
        LOG.infof("Checking coverage for policy %s", policyId);
        try {
            policyServiceClient.getPolicy(policyId);
            return true;
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 404) {
                LOG.warnf("Policy %s not found", policyId);
                return false;
            }
            throw e;
        }
    }

    /**
     * Fallback when the Policy service is unavailable (circuit open).
     * Returns false to reject the claim safely.
     */
    @SuppressWarnings("unused")
    private boolean coverageCheckFallback(String policyId) {
        LOG.errorf("Circuit breaker open - coverage check unavailable for policy %s", policyId);
        return false;
    }

    /**
     * MicroProfile REST client interface for the Policy service.
     */
    @RegisterRestClient(configKey = "policy-service")
    @Path("/api/policies")
    public interface PolicyServiceClient {

        @GET
        @Path("/{policyId}")
        Object getPolicy(@PathParam("policyId") String policyId);
    }
}
