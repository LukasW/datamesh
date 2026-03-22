package ch.yuno.claims.infrastructure.web;

import ch.yuno.claims.domain.model.Claim;
import ch.yuno.claims.domain.model.ClaimId;
import ch.yuno.claims.domain.model.ClaimStatus;
import ch.yuno.claims.domain.model.PartnerSearchView;
import ch.yuno.claims.domain.model.PolicySnapshot;
import ch.yuno.claims.domain.service.ClaimApplicationService;
import ch.yuno.claims.domain.service.ClaimNotFoundException;
import ch.yuno.claims.domain.service.CoverageCheckFailedException;
import ch.yuno.claims.infrastructure.persistence.ClaimJpaAdapter;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@Path("/claims")
@Produces(MediaType.TEXT_HTML)
public class ClaimUiController {

    @Location("schaeden/list")
    Template listTemplate;

    @Location("schaeden/form")
    Template formTemplate;

    @Location("schaeden/fragments/partner-search-results")
    Template partnerSearchResults;

    @Location("schaeden/fragments/policy-picker")
    Template policyPicker;

    @Location("schaeden/fragments/list-table")
    Template listTableFragment;

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

        List<Claim> claims = loadClaims(policyId, status, page, size);
        long total = claimJpaAdapter.countAll();
        long totalPages = Math.max(1, (total + size - 1) / size);

        return listTableFragment
                .data("schaeden", claims)
                .data("totalElements", total)
                .data("totalPages", totalPages)
                .data("currentPage", page)
                .data("pageSize", size)
                .data("hasNextPage", page + 1 < totalPages)
                .data("searchPolicyId", policyId != null ? policyId : "")
                .data("searchStatus", status != null ? status : "");
    }

    // ── Partner Search Fragments (FNOL) ───────────────────────────────────────

    @GET
    @Path("/fragments/partner-search")
    public TemplateInstance partnerSearch(
            @QueryParam("q") String nameQuery,
            @QueryParam("ahv") String ahvQuery,
            @QueryParam("vn") String vnQuery) {

        List<PartnerSearchView> partners;
        if (vnQuery != null && !vnQuery.isBlank()) {
            partners = claimService.findPartnerByInsuredNumber(vnQuery)
                    .map(List::of).orElse(List.of());
        } else if (ahvQuery != null && !ahvQuery.isBlank()) {
            partners = claimService.findPartnerBySocialSecurityNumber(ahvQuery)
                    .map(List::of).orElse(List.of());
        } else {
            partners = claimService.searchPartners(nameQuery);
        }
        return partnerSearchResults.data("partners", partners);
    }

    @GET
    @Path("/fragments/partner/{partnerId}/policies")
    public TemplateInstance partnerPolicies(@PathParam("partnerId") String partnerId) {
        List<PolicySnapshot> policies = claimService.findPoliciesForPartner(partnerId);
        return policyPicker.data("policies", policies);
    }

    @POST
    @Path("/fragments/{id}/review")
    public Response startReview(@PathParam("id") String claimId) {
        try {
            Claim claim = claimService.startReview(ClaimId.of(claimId));
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
            Claim claim = claimService.settle(ClaimId.of(claimId));
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
            Claim claim = claimService.reject(ClaimId.of(claimId));
            return Response.ok(renderRow(claim)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404).entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>").build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity("<div class='alert alert-danger'>" + e.getMessage() + "</div>").build();
        }
    }

    @GET
    @Path("/neu")
    public TemplateInstance newForm() {
        return formTemplate
                .data("claim", null)
                .data("errorMessage", null);
    }

    @POST
    @Path("/neu")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createClaim(
            @FormParam("policyId") String policyId,
            @FormParam("description") String description,
            @FormParam("claimDate") String claimDateStr) {
        try {
            LocalDate claimDate = LocalDate.parse(claimDateStr);
            claimService.openClaim(policyId, description, claimDate);
            return Response.seeOther(URI.create("/claims")).build();
        } catch (CoverageCheckFailedException e) {
            return Response.ok(formTemplate
                    .data("claim", null)
                    .data("policyId", policyId)
                    .data("description", description)
                    .data("claimDate", claimDateStr)
                    .data("errorMessage", "Keine aktive Police gefunden für ID: " + policyId))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.ok(formTemplate
                    .data("claim", null)
                    .data("policyId", policyId)
                    .data("description", description)
                    .data("claimDate", claimDateStr)
                    .data("errorMessage", e.getMessage()))
                    .build();
        }
    }

    /** Returns the inline edit form row for an OPEN claim (htmx swap). */
    @GET
    @Path("/fragments/{id}/edit")
    public Response editRow(@PathParam("id") String claimId) {
        try {
            Claim c = claimService.findById(ClaimId.of(claimId));
            return Response.ok(renderEditRow(c)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404)
                    .entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>")
                    .type(MediaType.TEXT_HTML).build();
        }
    }

    /** Saves inline edit and returns the updated normal row (htmx swap). */
    @POST
    @Path("/fragments/{id}/save")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveEdit(
            @PathParam("id") String claimId,
            @FormParam("description") String description,
            @FormParam("claimDate") String claimDateStr) {
        try {
            LocalDate claimDate = LocalDate.parse(claimDateStr);
            Claim updated = claimService.updateClaim(ClaimId.of(claimId), description, claimDate);
            return Response.ok(renderRow(updated)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404)
                    .entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>")
                    .type(MediaType.TEXT_HTML).build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            Claim c = claimService.findById(ClaimId.of(claimId));
            return Response.ok(renderEditRowWithError(c, e.getMessage())).type(MediaType.TEXT_HTML).build();
        }
    }

    /** Returns the read-only row for a claim (used by cancel in the edit form). */
    @GET
    @Path("/fragments/{id}/row")
    public Response viewRow(@PathParam("id") String claimId) {
        try {
            Claim c = claimService.findById(ClaimId.of(claimId));
            return Response.ok(renderRow(c)).type(MediaType.TEXT_HTML).build();
        } catch (ClaimNotFoundException e) {
            return Response.status(404)
                    .entity("<tr><td colspan='6' class='text-danger'>Schadenfall nicht gefunden</td></tr>")
                    .type(MediaType.TEXT_HTML).build();
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
                c.getClaimId().value(), c.getClaimNumber(), c.getPolicyId(),
                c.getDescription(), c.getClaimDate(), statusBadge, actions);
    }

    private String buildActionButtons(Claim c) {
        StringBuilder sb = new StringBuilder();
        if (c.getStatus() == ClaimStatus.OPEN) {
            sb.append("""
                    <button class="btn btn-sm btn-outline-secondary"
                            hx-get="/claims/fragments/%s/edit"
                            hx-target="#claim-%s" hx-swap="outerHTML">Ändern</button>
                    <button class="btn btn-sm btn-outline-warning ms-1"
                            hx-post="/claims/fragments/%s/review"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall in Bearbeitung nehmen?">In Bearbeitung</button>
                    <button class="btn btn-sm btn-outline-danger ms-1"
                            hx-post="/claims/fragments/%s/reject"
                            hx-target="#claim-%s" hx-swap="outerHTML"
                            hx-confirm="Schadenfall ablehnen?">Ablehnen</button>"""
                    .formatted(c.getClaimId().value(), c.getClaimId().value(),
                               c.getClaimId().value(), c.getClaimId().value(),
                               c.getClaimId().value(), c.getClaimId().value()));
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
                    .formatted(c.getClaimId().value(), c.getClaimId().value(),
                               c.getClaimId().value(), c.getClaimId().value()));
        }
        return sb.toString();
    }

    private String renderEditRow(Claim c) {
        return """
                <tr id="claim-%s">
                  <td colspan="6">
                    <form hx-post="/claims/fragments/%s/save"
                          hx-target="#claim-%s" hx-swap="outerHTML"
                          class="row g-2 align-items-center p-2">
                      <div class="col-auto">
                        <strong>%s</strong>
                        <br/><small class="text-muted">%s</small>
                      </div>
                      <div class="col-md-4">
                        <input type="text" class="form-control form-control-sm"
                               name="description" value="%s" required
                               placeholder="Schadensbeschreibung"/>
                      </div>
                      <div class="col-md-2">
                        <input type="date" class="form-control form-control-sm"
                               name="claimDate" value="%s" required/>
                      </div>
                      <div class="col-auto">
                        <button type="submit" class="btn btn-sm btn-success">Speichern</button>
                        <button type="button" class="btn btn-sm btn-secondary ms-1"
                                hx-get="/claims/fragments/%s/row"
                                hx-target="#claim-%s" hx-swap="outerHTML">Abbrechen</button>
                      </div>
                    </form>
                  </td>
                </tr>""".formatted(
                c.getClaimId().value(), c.getClaimId().value(), c.getClaimId().value(),
                c.getClaimNumber(), c.getPolicyId(),
                escapeHtml(c.getDescription()), c.getClaimDate(),
                c.getClaimId().value(), c.getClaimId().value());
    }

    private String renderEditRowWithError(Claim c, String errorMessage) {
        return """
                <tr id="claim-%s">
                  <td colspan="6">
                    <div class="alert alert-danger alert-dismissible py-1 mb-2" role="alert">
                      %s
                      <button type="button" class="btn-close btn-close-sm" data-bs-dismiss="alert"></button>
                    </div>
                    <form hx-post="/claims/fragments/%s/save"
                          hx-target="#claim-%s" hx-swap="outerHTML"
                          class="row g-2 align-items-center p-2">
                      <div class="col-auto">
                        <strong>%s</strong>
                        <br/><small class="text-muted">%s</small>
                      </div>
                      <div class="col-md-4">
                        <input type="text" class="form-control form-control-sm"
                               name="description" value="%s" required
                               placeholder="Schadensbeschreibung"/>
                      </div>
                      <div class="col-md-2">
                        <input type="date" class="form-control form-control-sm"
                               name="claimDate" value="%s" required/>
                      </div>
                      <div class="col-auto">
                        <button type="submit" class="btn btn-sm btn-success">Speichern</button>
                        <button type="button" class="btn btn-sm btn-secondary ms-1"
                                hx-get="/claims/fragments/%s/row"
                                hx-target="#claim-%s" hx-swap="outerHTML">Abbrechen</button>
                      </div>
                    </form>
                  </td>
                </tr>""".formatted(
                c.getClaimId().value(), escapeHtml(errorMessage),
                c.getClaimId().value(), c.getClaimId().value(),
                c.getClaimNumber(), c.getPolicyId(),
                escapeHtml(c.getDescription()), c.getClaimDate(),
                c.getClaimId().value(), c.getClaimId().value());
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                   .replace("\"", "&quot;").replace("'", "&#x27;");
    }
}
