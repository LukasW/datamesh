package ch.css.claims.infrastructure.web;

import ch.css.claims.domain.model.Claim;
import ch.css.claims.domain.service.ClaimApplicationService;
import ch.css.claims.domain.service.ClaimNotFoundException;
import ch.css.claims.domain.service.CoverageCheckFailedException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * REST adapter exposing claim operations via HTTP.
 */
@Path("/api/claims")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Claims", description = "Claims Management API")
public class ClaimRestAdapter {

    private static final Logger LOG = Logger.getLogger(ClaimRestAdapter.class);

    private final ClaimApplicationService claimService;

    public ClaimRestAdapter(ClaimApplicationService claimService) {
        this.claimService = claimService;
    }

    @POST
    @Operation(summary = "Open a new claim (First Notice of Loss)")
    public Response openClaim(OpenClaimRequest request) {
        LOG.infof("Received FNOL for policy %s", request.policyId());
        try {
            Claim claim = claimService.openClaim(request.policyId(), request.description(), request.claimDate());
            return Response.created(URI.create("/api/claims/" + claim.getClaimId()))
                    .entity(toResponse(claim)).build();
        } catch (CoverageCheckFailedException e) {
            LOG.warnf("Coverage check failed: %s", e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @GET
    @Path("/{claimId}")
    @Operation(summary = "Get a single claim by ID")
    public Response findById(@PathParam("claimId") String claimId) {
        try {
            return Response.ok(toResponse(claimService.findById(claimId))).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @GET
    @Operation(summary = "List all claims for a policy")
    public Response findByPolicy(@QueryParam("policyId") String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Query parameter 'policyId' is required")).build();
        }
        List<ClaimResponse> result = claimService.findByPolicyId(policyId).stream().map(this::toResponse).toList();
        return Response.ok(result).build();
    }

    @POST
    @Path("/{claimId}/review")
    @Operation(summary = "Start review of an open claim (OPEN → IN_REVIEW)")
    public Response startReview(@PathParam("claimId") String claimId) {
        try {
            return Response.ok(toResponse(claimService.startReview(claimId))).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{claimId}/settle")
    @Operation(summary = "Settle a claim under review (IN_REVIEW → SETTLED)")
    public Response settle(@PathParam("claimId") String claimId) {
        try {
            return Response.ok(toResponse(claimService.settle(claimId))).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/{claimId}/reject")
    @Operation(summary = "Reject a claim (OPEN or IN_REVIEW → REJECTED)")
    public Response reject(@PathParam("claimId") String claimId) {
        try {
            return Response.ok(toResponse(claimService.reject(claimId))).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(new ErrorResponse(e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    private ClaimResponse toResponse(Claim claim) {
        return new ClaimResponse(
                claim.getClaimId(), claim.getPolicyId(), claim.getClaimNumber(),
                claim.getDescription(), claim.getClaimDate(),
                claim.getStatus().name(), claim.getCreatedAt().toString());
    }

    public record OpenClaimRequest(String policyId, String description, LocalDate claimDate) {}
    public record ClaimResponse(String claimId, String policyId, String claimNumber,
                                String description, LocalDate claimDate,
                                String status, String createdAt) {}
    public record ErrorResponse(String message) {}
}
