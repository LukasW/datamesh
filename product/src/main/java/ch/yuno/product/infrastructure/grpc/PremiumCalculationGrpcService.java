package ch.yuno.product.infrastructure.grpc;

import ch.yuno.product.domain.model.PremiumCalculation;
import ch.yuno.product.domain.model.Product;
import ch.yuno.product.domain.model.RiskProfile;
import ch.yuno.product.domain.model.ProductId;
import ch.yuno.product.domain.port.out.ProductRepository;
import ch.yuno.product.domain.service.PremiumCalculationService;
import ch.yuno.product.application.ProductNotFoundException;
import ch.yuno.product.grpc.PremiumCalculationProto.PremiumCalculationRequest;
import ch.yuno.product.grpc.PremiumCalculationProto.PremiumCalculationResponse;
import ch.yuno.product.grpc.PremiumCalculationGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * gRPC Server Adapter for premium calculation (ADR-010).
 * Translates between Protobuf messages and domain objects.
 * This is a Driving Adapter in hexagonal architecture terms.
 */
@GrpcService
public class PremiumCalculationGrpcService extends PremiumCalculationGrpc.PremiumCalculationImplBase {

    private static final Logger LOG = Logger.getLogger(PremiumCalculationGrpcService.class);

    private final PremiumCalculationService premiumCalculationService = new PremiumCalculationService();

    @Inject
    ProductRepository productRepository;

    @Override
    public void calculatePremium(PremiumCalculationRequest request,
                                 StreamObserver<PremiumCalculationResponse> responseObserver) {
        LOG.infof("gRPC CalculatePremium called for productId=%s, age=%d, postalCode=%s",
                request.getProductId(), request.getAge(), request.getPostalCode());

        try {
            // 1. Look up the product
            Product product = productRepository.findById(ProductId.of(request.getProductId()))
                    .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

            if (!product.isActive()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Product is deprecated: " + request.getProductId())
                        .asRuntimeException());
                return;
            }

            // 2. Map request to domain value object
            RiskProfile riskProfile = new RiskProfile(
                    request.getAge(),
                    request.getPostalCode(),
                    request.getCanton(),
                    request.getCoverageTypesList()
            );

            // 3. Execute domain calculation
            PremiumCalculation result = premiumCalculationService.calculate(product, riskProfile);

            // 4. Map domain result to Protobuf response
            PremiumCalculationResponse response = PremiumCalculationResponse.newBuilder()
                    .setBasePremium(result.basePremium().toPlainString())
                    .setRiskSurcharge(result.riskSurcharge().toPlainString())
                    .setCoverageSurcharge(result.coverageSurcharge().toPlainString())
                    .setDiscount(result.discount().toPlainString())
                    .setTotalPremium(result.totalPremium().toPlainString())
                    .setCurrency(result.currency())
                    .setCalculationId(result.calculationId())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

            LOG.infof("Premium calculated: calculationId=%s, total=%s %s",
                    result.calculationId(), result.totalPremium(), result.currency());

        } catch (ProductNotFoundException e) {
            LOG.warnf("Product not found: %s", request.getProductId());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Product not found: " + request.getProductId())
                    .asRuntimeException());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid request: %s", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error during premium calculation");
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal error during premium calculation")
                    .asRuntimeException());
        }
    }
}

