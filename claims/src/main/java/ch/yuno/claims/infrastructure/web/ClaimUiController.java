package ch.yuno.claims.infrastructure.web;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimStatus;
import ch.yuno.claims.domain.service.ClaimApplicationService;
import ch.yuno.claims.domain.service.ClaimNotFoundException;
import ch.yuno.claims.infrastructure.persistence.ClaimJpaAdapter;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/claims")
@Produces(MediaType.TEXT_HTML)
public class ClaimUiController {

    @Location("schaeden/list")
    Template listTemplate;

    @Inject
    ClaimApplicationService claimService;

    @Inject
    ClaimJpaAdapter claimJpaAdapter;

    @GET
    public TemplateInstance list(
            @QueryParam("policyId") String policyId,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        List<Claim> claims = loadClaims(policyId, status, page, size);
        long total = claimJpaAdapter.countAll();
        long totalPages = Math.max(1, (total + size - 1) / size);

        return listTemplate
                .data("schaeden", claims)
                .data("totalElements", total)
                .data("totalPages", totalPages)
                .data("currentPage", page)
                .data("pageSize", size)
                .data("hasNextPage", page + 1 < totalPages)
                .data("searchPolicyId", policyId != null ? policyId : "")
                .data("searchStatus", status != null ? status : "");
    }

    @GET
    @Path("/fragments/list")
    public TemplateInstance listFragment(
            @QueryParam("policyId") String policyId,
            @QueryParam("status") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return list(policyId, status, page, size);
    }

    @POST
    @Path("/fragments/{id}/review")
    public Response startReview(@PathParam("id") String claimId) {
        try {
            Claim claim = claimService.startReview(claimId);
            return Response.ok(renderRow(claim)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    @POST
    @Path("/fragments/{id}/settle")
    public Response settle(@PathParam("id") String claimId) {
        try {
            Claim claim = claimService.settle(claimId);
            return Response.ok(renderRow(claim)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    @POST
    @Path("/fragments/{id}/reject")
    public Response reject(@PathParam("id") String claimId) {
        try {
            Claim claim = claimService.reject(claimId);
            return Response.ok(renderRow(claim)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    private List<Claim> loadClaims(String policyId, String status, int page, int size) {
        if (policyId != null && !policyId.isBlank()) {
            return claimService.findByPolicyId(policyId);
        }
        if (status != null && !status.isBlank()) {
            // filter in-memory from full page for simplicity
            ClaimStatus cs = ClaimStatus.valueOf(status.toUpperCase());
            return claimJpaAdapter.findAll(page, size).stream()
                    .filter(c -> c.getStatus() == cs).toList();
        }
        return claimJpaAdapter.findAll(page, size);
    }

    private String renderRow(Claim c) {
        String statusBadge = "<span class=\"badge badge-" + c.getStatus().name() + "\">" + c.getStatus().name() + "</span>";
        String actions = buildActionButtons(c);
        return """
                <tr id="claim-%s">
                  <td><strong>%s</strong></td>
                  <td><small class="text-muted">%s</small></td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                  <td>%s</td>
                </tr>""".formatted(
                c.getClaimId(), c.getClaimNumber(), c.getPolicyId(),
                c.getDescription(), c.getClaimDate(), statusBadge, actions);
    }

    private String buildActionButtons(Claim c) {
        StringBuilder sb = new StringBuilder();
        if (c.getStatus() == ClaimStatus.OPEN) {
            sb.append("""
                    <button class="btn btn-sm btn-outline-warning"
                            hx-post="/claims/fragments/%s/review"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall in Bearbeitung nehmen?">Bearbeiten</button>
                    <button class="btn btn-sm btn-outline-danger ms-1"
                            hx-post="/claims/fragments/%s/reject"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall ablehnen?">Ablehnen</button>"""
                    .formatted(c.getClaimId(), c.getClaimId(), c.getClaimId(), c.getClaimId()));
        } else if (c.getStatus() == ClaimStatus.IN_REVIEW) {
            sb.append("""
                    <button class="btn btn-sm btn-outline-success"
                            hx-post="/claims/fragments/%s/settle"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall regulieren?">Regulieren</button>
                    <button class="btn btn-sm btn-outline-danger ms-1"
                            hx-post="/claims/fragments/%s/reject"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall ablehnen?">Ablehnen</button>"""
                    .formatted(c.getClaimId(), c.getClaimId(), c.getClaimId(), c.getClaimId()));
        }
        return sb.toString();
    }
}
