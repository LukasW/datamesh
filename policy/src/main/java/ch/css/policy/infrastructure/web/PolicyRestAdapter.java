package ch.css.policy.infrastructure.web;

import ch.css.policy.domain.model.Deckung;
import ch.css.policy.domain.model.Deckungstyp;
import ch.css.policy.domain.model.Policy;
import ch.css.policy.domain.model.PolicyStatus;
import ch.css.policy.domain.service.DeckungNotFoundException;
import ch.css.policy.domain.service.PolicyApplicationService;
import ch.css.policy.domain.service.PolicyNotFoundException;
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
@Path("/api/policen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PolicyRestAdapter {

    @Inject
    PolicyApplicationService policyService;

    // ── Policy CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response createPolicy(CreatePolicyRequest req) {
        try {
            String id = policyService.createPolicy(
                    req.policyNummer(), req.partnerId(), req.produktId(),
                    req.versicherungsbeginn(), req.versicherungsende(),
                    req.praemie(), req.selbstbehalt() != null ? req.selbstbehalt() : BigDecimal.ZERO);
            return Response.status(201).entity(Map.of("id", id)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response searchPolicen(
            @QueryParam("policyNummer") String policyNummer,
            @QueryParam("partnerId") String partnerId,
            @QueryParam("status") String status) {
        try {
            List<PolicyDto> result = policyService.searchPolicen(policyNummer, partnerId, status)
                    .stream().map(PolicyDto::from).toList();
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getPolicy(@PathParam("id") String id) {
        try {
            return Response.ok(PolicyDto.from(policyService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePolicy(@PathParam("id") String id, UpdatePolicyRequest req) {
        try {
            policyService.updatePolicyDetails(id, req.produktId(),
                    req.versicherungsbeginn(), req.versicherungsende(),
                    req.praemie(), req.selbstbehalt() != null ? req.selbstbehalt() : BigDecimal.ZERO);
            return Response.ok(PolicyDto.from(policyService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/aktivieren")
    public Response aktivierePolicy(@PathParam("id") String id) {
        try {
            policyService.aktivierePolicy(id);
            return Response.ok(PolicyDto.from(policyService.findById(id))).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/kuendigen")
    public Response kuendigePolicy(@PathParam("id") String id) {
        try {
            policyService.kuendigePolicy(id);
            return Response.ok(PolicyDto.from(policyService.findById(id))).build();
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
            policyService.findById(id); // throws if not found
            // deletion only allowed for ENTWURF policies - check status
            Policy policy = policyService.findById(id);
            if (policy.getStatus() != PolicyStatus.ENTWURF) {
                return Response.status(409).entity(Map.of("message", "Nur Entwürfe können gelöscht werden")).build();
            }
            // direct delete via repository not exposed, use a workaround: cancel + flag
            // For now, throw 405 for non-ENTWURF; ENTWURF can be deleted
            return Response.status(405).entity(Map.of("message", "Löschen nicht unterstützt – verwende Kündigen")).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Deckungen ─────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/deckungen")
    public Response getDeckungen(@PathParam("id") String id) {
        try {
            List<DeckungDto> result = policyService.findById(id)
                    .getDeckungen().stream().map(DeckungDto::from).toList();
            return Response.ok(result).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/deckungen")
    public Response addDeckung(@PathParam("id") String id, AddDeckungRequest req) {
        try {
            String deckungId = policyService.addDeckung(
                    id, Deckungstyp.valueOf(req.deckungstyp()), req.versicherungssumme());
            Deckung deckung = policyService.findById(id).getDeckungen().stream()
                    .filter(d -> d.getDeckungId().equals(deckungId))
                    .findFirst().orElseThrow();
            return Response.status(201).entity(DeckungDto.from(deckung)).build();
        } catch (PolicyNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/deckungen/{did}")
    public Response removeDeckung(@PathParam("id") String id, @PathParam("did") String did) {
        try {
            policyService.removeDeckung(id, did);
            return Response.noContent().build();
        } catch (PolicyNotFoundException | DeckungNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Request / Response DTOs ───────────────────────────────────────────────

    public record CreatePolicyRequest(
            String policyNummer, String partnerId, String produktId,
            LocalDate versicherungsbeginn, LocalDate versicherungsende,
            BigDecimal praemie, BigDecimal selbstbehalt) {}

    public record UpdatePolicyRequest(
            String produktId, LocalDate versicherungsbeginn, LocalDate versicherungsende,
            BigDecimal praemie, BigDecimal selbstbehalt) {}

    public record AddDeckungRequest(String deckungstyp, BigDecimal versicherungssumme) {}

    public record PolicyDto(
            String policyId, String policyNummer, String partnerId, String produktId,
            String status, LocalDate versicherungsbeginn, LocalDate versicherungsende,
            BigDecimal praemie, BigDecimal selbstbehalt, List<DeckungDto> deckungen) {

        public static PolicyDto from(Policy p) {
            return new PolicyDto(
                    p.getPolicyId(), p.getPolicyNummer(), p.getPartnerId(), p.getProduktId(),
                    p.getStatus().name(), p.getVersicherungsbeginn(), p.getVersicherungsende(),
                    p.getPraemie(), p.getSelbstbehalt(),
                    p.getDeckungen().stream().map(DeckungDto::from).toList());
        }
    }

    public record DeckungDto(
            String deckungId, String policyId, String deckungstyp, BigDecimal versicherungssumme) {

        public static DeckungDto from(Deckung d) {
            return new DeckungDto(d.getDeckungId(), d.getPolicyId(),
                    d.getDeckungstyp().name(), d.getVersicherungssumme());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}

