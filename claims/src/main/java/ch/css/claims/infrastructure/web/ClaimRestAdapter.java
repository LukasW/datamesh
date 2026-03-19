package ch.css.claims.infrastructure.web;

import ch.css.claims.domain.model.Claim;
import ch.css.claims.domain.service.ClaimApplicationService;
import ch.css.claims.domain.service.ClaimNotFoundException;
import ch.css.claims.domain.service.CoverageCheckFailedException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.LocalDate;

/**
 * REST adapter exposing claim operations via HTTP.
 */
@Path("/api/claims")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClaimRestAdapter {

    private static final Logger LOG = Logger.getLogger(ClaimRestAdapter.class);

    private final ClaimApplicationService claimService;

    public ClaimRestAdapter(ClaimApplicationService claimService) {
        this.claimService = claimService;
    }

    /**
     * Create a new claim (First Notice of Loss).
     */
    @POST
    public Response openClaim(OpenClaimRequest request) {
        LOG.infof("Received FNOL for policy %s", request.policyId());
        try {
            Claim claim = claimService.openClaim(
                    request.policyId(),
                    request.description(),
                    request.claimDate()
            );
            return Response.created(URI.create("/api/claims/" + claim.getClaimId()))
                    .entity(toResponse(claim))
                    .build();
        } catch (CoverageCheckFailedException e) {
            LOG.warnf("Coverage check failed: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }

    /**
     * Retrieve a claim by its ID.
     */
    @GET
    @Path("/{claimId}")
    public Response findById(@PathParam("claimId") String claimId) {
        try {
            Claim claim = claimService.findById(claimId);
            return Response.ok(toResponse(claim)).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }
    }

    private ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getClaimId(),
                claim.getPolicyId(),
                claim.getClaimNumber(),
                claim.getDescription(),
                claim.getClaimDate(),
                claim.getStatus().name(),
                claim.getCreatedAt().toString()
        );
    }

    // --- DTOs ---

    public record OpenClaimRequest(String policyId, String description, LocalDate claimDate) {}

    public record ClaimResponse(String claimId, String policyId, String claimNumber,
                                String description, LocalDate claimDate,
                                String status, String createdAt) {}

    public record ErrorResponse(String message) {}
}
