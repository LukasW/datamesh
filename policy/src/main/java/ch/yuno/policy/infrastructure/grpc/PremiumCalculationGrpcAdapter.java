package ch.yuno.policy.infrastructure.grpc;

import ch.yuno.policy.domain.model.PremiumCalculationResult;
import ch.yuno.policy.domain.port.out.PremiumCalculationPort;
import ch.yuno.policy.domain.service.PremiumCalculationUnavailableException;
import ch.yuno.product.grpc.PremiumCalculationGrpc;
import ch.yuno.product.grpc.PremiumCalculationProto.PremiumCalculationRequest;
import ch.yuno.product.grpc.PremiumCalculationProto.PremiumCalculationResponse;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;

/**
 * gRPC Client Adapter for premium calculation (ADR-010).
 * Implements the PremiumCalculationPort by calling the Product Service's gRPC endpoint.
 * This is a Driven Adapter in hexagonal architecture terms.
 * <p>
 * Includes MicroProfile Fault Tolerance annotations:
 * - CircuitBreaker: opens after 50% failure rate within 4 requests, 10s delay before half-open
 * - Timeout: 2 seconds per request
 * - Retry: up to 2 retries with 500ms delay
 */
@ApplicationScoped
public class PremiumCalculationGrpcAdapter implements PremiumCalculationPort {

    private static final Logger LOG = Logger.getLogger(PremiumCalculationGrpcAdapter.class);

    @GrpcClient("premium-calculation")
    PremiumCalculationGrpc.PremiumCalculationBlockingStub client;

    @Override
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000L)
    @Timeout(2000)
    @Retry(maxRetries = 2, delay = 500L)
    public PremiumCalculationResult calculatePremium(
            String productId,
            String productLine,
            int age,
            String postalCode,
            List<String> coverageTypes) {

        LOG.infof("Calling Product gRPC for premium calculation: productId=%s, age=%d, postalCode=%s",
                productId, age, postalCode);

        try {
            PremiumCalculationRequest request = PremiumCalculationRequest.newBuilder()
                    .setProductId(productId)
                    .setProductLine(productLine != null ? productLine : "")
                    .setAge(age)
                    .setPostalCode(postalCode != null ? postalCode : "")
                    .addAllCoverageTypes(coverageTypes != null ? coverageTypes : List.of())
                    .build();

            PremiumCalculationResponse response = client.calculatePremium(request);

            PremiumCalculationResult result = new PremiumCalculationResult(
                    response.getCalculationId(),
                    new BigDecimal(response.getBasePremium()),
                    new BigDecimal(response.getRiskSurcharge()),
                    new BigDecimal(response.getCoverageSurcharge()),
                    new BigDecimal(response.getDiscount()),
                    new BigDecimal(response.getTotalPremium()),
                    response.getCurrency()
            );

            LOG.infof("Premium calculated successfully: calculationId=%s, total=%s %s",
                    result.calculationId(), result.totalPremium(), result.currency());

            return result;

        } catch (StatusRuntimeException e) {
            LOG.errorf("gRPC call failed: status=%s, description=%s",
                    e.getStatus().getCode(), e.getStatus().getDescription());
            throw new PremiumCalculationUnavailableException(
                    "Premium calculation service unavailable: " + e.getStatus().getDescription(), e);
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error calling premium calculation service");
            throw new PremiumCalculationUnavailableException(
                    "Premium calculation service unavailable", e);
        }
    }
}

