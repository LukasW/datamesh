package ch.css.policy.infrastructure.web;

import ch.css.policy.domain.model.Deckung;
import ch.css.policy.domain.model.Deckungstyp;
import ch.css.policy.domain.model.PartnerSicht;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.service.DeckungNotFoundException;
import ch.css.policy.domain.service.PolicyApplicationService;
import ch.css.policy.domain.service.PolicyNotFoundException;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Qute UI Controller for the Policenverwaltung screens.
 * Handles full-page renders and htmx fragment endpoints.
 */
@Path("/policen")
@Produces(MediaType.TEXT_HTML)
public class PolicyUiController {

    @Inject
    PolicyApplicationService policyService;

    @Inject
    @Location("policen/list")
    Template list;

    @Inject
    @Location("policen/edit")
    Template edit;

    @Inject
    @Location("policen/fragments/policen-row")
    Template policenRow;

    @Inject
    @Location("policen/fragments/policen-form-modal")
    Template policenFormModal;

    @Inject
    @Location("policen/fragments/policy-details-form")
    Template policyDetailsForm;

    @Inject
    @Location("policen/fragments/deckung-karte")
    Template deckungKarte;

    @Inject
    @Location("policen/fragments/deckung-form")
    Template deckungForm;

    @Inject
    @Location("policen/fragments/partner-suchen-widget")
    Template partnerSuchenWidget;

    @Inject
    @Location("policen/fragments/partner-picker-selected")
    Template partnerPickerSelected;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance getList() {
        return list.data("policen", policyService.listAllPolicen())
                   .data("partnerSichten", policyService.getPartnerSichtenMap())
                   .data("produktSichten", policyService.getProduktSichtenMap());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Policy policy = policyService.findById(id);
            return edit.data("policy", policy)
                       .data("activeProdukte", policyService.getActiveProdukte())
                       .data("partnerSichten", policyService.getPartnerSichtenMap())
                       .data("produktSichten", policyService.getProduktSichtenMap())
                       .data("success", false)
                       .data("errorMessage", null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>Police nicht gefunden: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPolicenListFragment(
            @QueryParam("policyNummer") String policyNummer,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("status") String status) {
        List<Policy> policen;
        try {
            policen = policyService.searchPolicen(policyNummer, partnerId, status);
        } catch (Exception ignored) {
            policen = List.of();
        }
        return list.getFragment("tabelle")
                   .data("policen", policen)
                   .data("partnerSichten", policyService.getPartnerSichtenMap())
                   .data("produktSichten", policyService.getProduktSichtenMap());
    }

    /** Returns the new-policy modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getPolicenFormModal() {
        return policenFormModal
                .data("activeProdukte", policyService.getActiveProdukte());
    }

    /**
     * Creates a new policy from modal form data and returns a table row fragment.
     * On validation error returns 422 with an error alert HTML.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object createPolicyFragment(
            @FormParam("partnerId") String partnerId,
            @FormParam("produktId") String produktId,
            @FormParam("versicherungsbeginn") String versicherungsbeginn,
            @FormParam("versicherungsende") String versicherungsende,
            @FormParam("praemie") String praemie,
            @FormParam("selbstbehalt") String selbstbehalt) {
        try {
            LocalDate beginn = LocalDate.parse(versicherungsbeginn);
            LocalDate ende = isNotBlank(versicherungsende) ? LocalDate.parse(versicherungsende) : null;
            BigDecimal p = new BigDecimal(praemie);
            BigDecimal sb = isNotBlank(selbstbehalt) ? new BigDecimal(selbstbehalt) : BigDecimal.ZERO;
            String id = policyService.createPolicy(partnerId, produktId, beginn, ende, p, sb);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerSichtenMap())
                             .data("produktSichten", policyService.getProduktSichtenMap());
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns the inline details edit form. */
    @GET
    @Path("/fragments/{id}/details-form")
    public Object getDetailsForm(@PathParam("id") String id) {
        try {
            Policy policy = policyService.findById(id);
            return renderPolicyDetailsForm(policy, false, null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /**
     * Updates policy details from form data and re-renders the details form.
     */
    @PUT
    @Path("/fragments/{id}/details")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updateDetailsFragment(
            @PathParam("id") String policyId,
            @FormParam("produktId") String produktId,
            @FormParam("versicherungsbeginn") String versicherungsbeginn,
            @FormParam("versicherungsende") String versicherungsende,
            @FormParam("praemie") String praemie,
            @FormParam("selbstbehalt") String selbstbehalt) {
        try {
            LocalDate beginn = LocalDate.parse(versicherungsbeginn);
            LocalDate ende = isNotBlank(versicherungsende) ? LocalDate.parse(versicherungsende) : null;
            BigDecimal p = new BigDecimal(praemie);
            BigDecimal sb = isNotBlank(selbstbehalt) ? new BigDecimal(selbstbehalt) : BigDecimal.ZERO;
            policyService.updatePolicyDetails(policyId, produktId, beginn, ende, p, sb);
            Policy policy = policyService.findById(policyId);
            return renderPolicyDetailsForm(policy, true, null);
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            try {
                Policy policy = policyService.findById(policyId);
                return renderPolicyDetailsForm(policy, false,
                        "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>");
            } catch (Exception ex) {
                return Response.status(422)
                        .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                        .build();
            }
        }
    }

    /** Activates a policy and returns the refreshed table row. */
    @POST
    @Path("/fragments/{id}/aktivieren")
    public Object aktivierenFragment(@PathParam("id") String id) {
        try {
            policyService.aktivierePolicy(id);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerSichtenMap())
                             .data("produktSichten", policyService.getProduktSichtenMap());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("<div class=\"alert alert-warning\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Cancels a policy and returns the refreshed table row. */
    @POST
    @Path("/fragments/{id}/kuendigen")
    public Object kuendigenFragment(@PathParam("id") String id) {
        try {
            policyService.kuendigePolicy(id);
            Policy policy = policyService.findById(id);
            return policenRow.data("policy", policy)
                             .data("partnerSichten", policyService.getPartnerSichtenMap())
                             .data("produktSichten", policyService.getProduktSichtenMap());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity("<div class=\"alert alert-warning\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns an empty deckung-form for adding a new coverage. */
    @GET
    @Path("/fragments/{id}/deckung-form")
    public TemplateInstance getDeckungForm(@PathParam("id") String id) {
        return deckungForm.data("policyId", id).data("errorMessage", null);
    }

    /** Returns empty string to remove the deckung-form from the DOM (cancel). */
    @GET
    @Path("/fragments/{id}/deckung-form-cancel")
    public String cancelDeckungForm() {
        return "";
    }

    /**
     * Creates a new coverage from form data and returns the deckung-karte fragment.
     * On error re-renders the form with an error message.
     */
    @POST
    @Path("/fragments/{id}/deckungen")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object addDeckungFragment(
            @PathParam("id") String policyId,
            @FormParam("deckungstyp") String deckungstyp,
            @FormParam("versicherungssumme") String versicherungssumme) {
        try {
            BigDecimal summe = new BigDecimal(versicherungssumme);
            String deckungId = policyService.addDeckung(policyId, Deckungstyp.valueOf(deckungstyp), summe);
            Deckung deckung = policyService.findById(policyId).getDeckungen().stream()
                    .filter(d -> d.getDeckungId().equals(deckungId))
                    .findFirst().orElseThrow();
            Policy policy = policyService.findById(policyId);
            return deckungKarte
                    .data("deckung", deckung)
                    .data("policyId", policyId)
                    .data("policyStatus", policy.getStatus().name());
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return deckungForm
                    .data("policyId", policyId)
                    .data("errorMessage", "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>");
        }
    }

    /** Returns the deckung-karte fragment (used for cancel of inline edit). */
    @GET
    @Path("/fragments/{id}/deckungen/{did}/karte")
    public Object getDeckungKarte(@PathParam("id") String policyId, @PathParam("did") String did) {
        try {
            Deckung deckung = policyService.findById(policyId).getDeckungen().stream()
                    .filter(d -> d.getDeckungId().equals(did))
                    .findFirst()
                    .orElseThrow(() -> new DeckungNotFoundException(did));
            Policy policy = policyService.findById(policyId);
            return deckungKarte
                    .data("deckung", deckung)
                    .data("policyId", policyId)
                    .data("policyStatus", policy.getStatus().name());
        } catch (PolicyNotFoundException | DeckungNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    // ── Partner Search ────────────────────────────────────────────────────────

    /**
     * Returns the partner search widget with filtered results.
     * Called via htmx to load the search panel and on every keyup in the search input.
     */
    @GET
    @Path("/fragments/partner-suche")
    public TemplateInstance getPartnerSucheWidget(@QueryParam("q") String q) {
        List<PartnerSicht> partners = policyService.searchPartnerSichten(q);
        return partnerSuchenWidget
                .data("partners", partners)
                .data("q", q != null ? q : "");
    }

    /**
     * Selects a partner and returns the partner-picker div in "selected" state.
     * Targeted with hx-swap="outerHTML" on #partner-picker.
     */
    @POST
    @Path("/fragments/partner-auswaehlen/{partnerId}")
    public Object selectPartner(@PathParam("partnerId") String partnerId) {
        return policyService.findPartnerSicht(partnerId)
                .map(p -> (Object) partnerPickerSelected
                        .data("partnerId", p.getPartnerId())
                        .data("partnerName", p.getName()))
                .orElse(Response.status(404)
                        .entity("<div class=\"alert alert-danger\">Partner nicht gefunden: "
                                + escapeHtml(partnerId) + "</div>")
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private TemplateInstance renderPolicyDetailsForm(Policy policy, boolean success, String errorMessage) {
        return policyDetailsForm
                .data("policy", policy)
                .data("activeProdukte", policyService.getActiveProdukte())
                .data("partnerSichten", policyService.getPartnerSichtenMap())
                .data("success", success)
                .data("errorMessage", errorMessage);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

