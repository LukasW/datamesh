package ch.yuno.policy.infrastructure.web;

import ch.yuno.policy.domain.model.Coverage;
import ch.yuno.policy.domain.model.CoverageType;
import ch.yuno.policy.domain.model.PageRequest;
import ch.yuno.policy.domain.model.PageResult;
import ch.yuno.policy.domain.model.Policy;
import ch.yuno.policy.domain.model.PolicyStatus;
import ch.yuno.policy.domain.service.CoverageNotFoundException;
import ch.yuno.policy.domain.service.PolicyCommandService;
import ch.yuno.policy.domain.service.PolicyNotFoundException;
import ch.yuno.policy.domain.service.PolicyQueryService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST adapter for Policy CRUD and Coverage sub-resource.
 */
@Path("/api/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PolicyRestAdapter {

    @Inject
    PolicyCommandService policyCommandService;

    @Inject
    PolicyQueryService policyQueryService;

    // ── Policy CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response createPolicy(CreatePolicyRequest req) {
        try {
            String id = policyCommandService.createPolicy(
                    req.partnerId(), req.productId(),
                    req.coverageStartDate(), req.coverageEndDate(),
                    req.premium(), req.deductible() != null ? req.deductible() : BigDecimal.ZERO);
            return Response.status(201).entity(Map.of("id", id)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response searchPolicies(
            @QueryParam("policyNumber") String policyNumber,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        try {
            PageRequest pageRequest = new PageRequest(page, size);
            PageResult<Policy> pageResult = policyQueryService.searchPolicies(policyNumber, partnerId, status, pageRequest);
            PagedResponse<PolicyDto> response = new PagedResponse<>(
                    pageResult.content().stream().map(PolicyDto::from).toList(),
                    pageResult.totalElements(),
                    pageResult.totalPages(),
                    page, size);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getPolicy(@PathParam("id") String id) {
        try {
            return Response.ok(PolicyDto.from(policyQueryService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePolicy(@PathParam("id") String id, UpdatePolicyRequest req) {
        try {
            policyCommandService.updatePolicyDetails(id, req.productId(),
                    req.coverageStartDate(), req.coverageEndDate(),
                    req.premium(), req.deductible() != null ? req.deductible() : BigDecimal.ZERO);
            return Response.ok(PolicyDto.from(policyQueryService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/activate")
    public Response activatePolicy(@PathParam("id") String id) {
        try {
            policyCommandService.activatePolicy(id);
            return Response.ok(PolicyDto.from(policyQueryService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancelPolicy(@PathParam("id") String id) {
        try {
            policyCommandService.cancelPolicy(id);
            return Response.ok(PolicyDto.from(policyQueryService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deletePolicy(@PathParam("id") String id) {
        try {
            policyQueryService.findById(id); // throws if not found
            // deletion only allowed for DRAFT policies - check status
            Policy policy = policyQueryService.findById(id);
            if (policy.getStatus() != PolicyStatus.DRAFT) {
                return Response.status(409).entity(Map.of("message", "Only DRAFT policies can be deleted")).build();
            }
            // direct delete via repository not exposed, use a workaround: cancel + flag
            // For now, throw 405 for non-DRAFT; DRAFT can be deleted
            return Response.status(405).entity(Map.of("message", "Delete not supported – use cancel")).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Coverages ─────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/coverages")
    public Response getCoverages(@PathParam("id") String id) {
        try {
            List<CoverageDto> result = policyQueryService.findById(id)
                    .getCoverages().stream().map(CoverageDto::from).toList();
            return Response.ok(result).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/coverages")
    public Response addCoverage(@PathParam("id") String id, AddCoverageRequest req) {
        try {
            String coverageId = policyCommandService.addCoverage(
                    id, CoverageType.valueOf(req.coverageType()), req.insuredAmount());
            Coverage coverage = policyQueryService.findById(id).getCoverages().stream()
                    .filter(c -> c.getCoverageId().equals(coverageId))
                    .findFirst().orElseThrow();
            return Response.status(201).entity(CoverageDto.from(coverage)).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/coverages/{did}")
    public Response removeCoverage(@PathParam("id") String id, @PathParam("did") String did) {
        try {
            policyCommandService.removeCoverage(id, did);
            return Response.noContent().build();
        } catch (PolicyNotFoundException | CoverageNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Request / Response DTOs ───────────────────────────────────────────────

    public record CreatePolicyRequest(
            String partnerId, String productId,
            LocalDate coverageStartDate, LocalDate coverageEndDate,
            BigDecimal premium, BigDecimal deductible) {}

    public record UpdatePolicyRequest(
            String productId, LocalDate coverageStartDate, LocalDate coverageEndDate,
            BigDecimal premium, BigDecimal deductible) {}

    public record AddCoverageRequest(String coverageType, BigDecimal insuredAmount) {}

    public record PolicyDto(
            String policyId, String policyNumber, String partnerId, String productId,
            String status, LocalDate coverageStartDate, LocalDate coverageEndDate,
            BigDecimal premium, BigDecimal deductible, List<CoverageDto> coverages) {

        public static PolicyDto from(Policy p) {
            return new PolicyDto(
                    p.getPolicyId(), p.getPolicyNumber(), p.getPartnerId(), p.getProductId(),
                    p.getStatus().name(), p.getCoverageStartDate(), p.getCoverageEndDate(),
                    p.getPremium(), p.getDeductible(),
                    p.getCoverages().stream().map(CoverageDto::from).toList());
        }
    }

    public record CoverageDto(
            String coverageId, String policyId, String coverageType, BigDecimal insuredAmount) {

        public static CoverageDto from(Coverage c) {
            return new CoverageDto(c.getCoverageId(), c.getPolicyId(),
                    c.getCoverageType().name(), c.getInsuredAmount());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public record PagedResponse<T>(
            List<T> content, long totalElements, int totalPages, int page, int size) {}

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
