package ch.css.partner.infrastructure.web;

import ch.css.partner.domain.model.Adresse;
import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.Geschlecht;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.service.AdressUeberschneidungException;
import ch.css.partner.domain.service.AdresseNotFoundException;
import ch.css.partner.domain.service.PersonApplicationService;
import ch.css.partner.domain.service.PersonNotFoundException;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;

/**
 * Qute UI Controller for the Personenverwaltung screens.
 * Handles full-page renders and htmx fragment endpoints.
 */
@Path("/personen")
@Produces(MediaType.TEXT_HTML)
public class PersonUiController {

    @Inject
    PersonApplicationService personService;

    @Inject
    @Location("personen/list")
    Template list;

    @Inject
    @Location("personen/edit")
    Template edit;

    @Inject
    @Location("personen/fragments/personen-row")
    Template personenRow;

    @Inject
    @Location("personen/fragments/personen-form-modal")
    Template personenFormModal;

    @Inject
    @Location("personen/fragments/adresse-form")
    Template adresseForm;

    @Inject
    @Location("personen/fragments/adresse-karte")
    Template adresseKarte;

    @Inject
    @Location("personen/fragments/adresse-gueltigkeit-form")
    Template adresseGueltigkeitForm;

    @Inject
    @Location("personen/fragments/personalien-form")
    Template personalienForm;

    // ── Full Pages ────────────────────────────────────────────────────────────

    @GET
    public TemplateInstance getList() {
        return list.data("personen", personService.listAllPersonen());
    }

    @GET
    @Path("/{id}/edit")
    public Object getEdit(@PathParam("id") String id) {
        try {
            Person person = personService.findById(id);
            return edit.data("person", person);
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity("<p>Person nicht gefunden: " + id + "</p>").build();
        }
    }

    // ── htmx Fragments ────────────────────────────────────────────────────────

    /** Renders the filtered table rows (htmx live search). */
    @GET
    @Path("/fragments/list")
    public TemplateInstance getPersonenListFragment(
            @QueryParam("name") String name,
            @QueryParam("vorname") String vorname,
            @QueryParam("ahv") String ahv) {

        boolean hasFilter = isNotBlank(name) || isNotBlank(vorname) || isNotBlank(ahv);
        List<Person> personen;
        try {
            personen = hasFilter
                    ? personService.searchPersonen(name, vorname, ahv, null)
                    : personService.listAllPersonen();
        } catch (Exception ignored) {
            personen = List.of();
        }
        return list.getFragment("tabelle").data("personen", personen);
    }

    /** Returns the new-person modal form. */
    @GET
    @Path("/fragments/neu")
    public TemplateInstance getPersonenFormModal() {
        return personenFormModal.instance();
    }

    /**
     * Creates a new person from modal form data and returns a table row fragment.
     * On validation error returns 422 with an error alert HTML.
     */
    @POST
    @Path("/fragments")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object createPersonFragment(
            @FormParam("name") String name,
            @FormParam("vorname") String vorname,
            @FormParam("geschlecht") String geschlecht,
            @FormParam("geburtsdatum") String geburtsdatum,
            @FormParam("ahvNummer") String ahvNummer) {
        try {
            String id = personService.createPerson(
                    name, vorname,
                    Geschlecht.valueOf(geschlecht),
                    LocalDate.parse(geburtsdatum),
                    ahvNummer);
            Person person = personService.findById(id);
            return personenRow.data("person", person);
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns an empty adresse-form for adding a new address. */
    @GET
    @Path("/fragments/{id}/adresse-form")
    public TemplateInstance getAdresseForm(@PathParam("id") String id) {
        return adresseForm.data("personId", id).data("errorMessage", null);
    }

    /** Returns empty string to remove the adresse-form from the DOM (cancel). */
    @GET
    @Path("/fragments/{id}/adresse-form-cancel")
    public String cancelAdresseForm() {
        return "";
    }

    /**
     * Creates a new address from form data and returns the adresse-karte fragment.
     * On conflict (409) re-renders the form with an error message.
     */
    @POST
    @Path("/fragments/{id}/adressen")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object addAdresseFragment(
            @PathParam("id") String personId,
            @FormParam("adressTyp") String adressTyp,
            @FormParam("strasse") String strasse,
            @FormParam("hausnummer") String hausnummer,
            @FormParam("plz") String plz,
            @FormParam("ort") String ort,
            @FormParam("land") String land,
            @FormParam("gueltigVon") String gueltigVon,
            @FormParam("gueltigBis") String gueltigBis) {
        try {
            LocalDate von = LocalDate.parse(gueltigVon);
            LocalDate bis = isNotBlank(gueltigBis) ? LocalDate.parse(gueltigBis) : null;
            String adressId = personService.addAdresse(
                    personId, AdressTyp.valueOf(adressTyp),
                    strasse, hausnummer, plz, ort,
                    isNotBlank(land) ? land : "Schweiz",
                    von, bis);
            Adresse adresse = personService.findById(personId).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(adressId))
                    .findFirst().orElseThrow();
            return adresseKarte.data("adresse", adresse).data("personId", personId);
        } catch (AdressUeberschneidungException e) {
            String error = "<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>";
            return adresseForm
                    .data("personId", personId)
                    .data("errorMessage", error);
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /** Returns the adresse-karte fragment (used for cancel of gueltigkeit edit). */
    @GET
    @Path("/fragments/{id}/adressen/{aid}/karte")
    public Object getAdresseKarte(@PathParam("id") String personId, @PathParam("aid") String aid) {
        try {
            Adresse adresse = personService.findById(personId).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(aid))
                    .findFirst()
                    .orElseThrow(() -> new AdresseNotFoundException(aid));
            return adresseKarte.data("adresse", adresse).data("personId", personId);
        } catch (PersonNotFoundException | AdresseNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /** Returns the inline gueltigkeit edit form for an address card. */
    @GET
    @Path("/fragments/{id}/adressen/{aid}/gueltigkeit")
    public Object getAdresseGueltigkeitForm(@PathParam("id") String personId,
                                             @PathParam("aid") String aid) {
        try {
            Adresse adresse = personService.findById(personId).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(aid))
                    .findFirst()
                    .orElseThrow(() -> new AdresseNotFoundException(aid));
            return adresseGueltigkeitForm.data("adresse", adresse).data("personId", personId);
        } catch (PersonNotFoundException | AdresseNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        }
    }

    /**
     * Updates address validity period from inline form and returns refreshed adresse-karte.
     * On conflict re-renders the gueltigkeit form with an error.
     */
    @PUT
    @Path("/fragments/{id}/adressen/{aid}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updateAdresseGueltigkeitFragment(
            @PathParam("id") String personId,
            @PathParam("aid") String aid,
            @FormParam("gueltigVon") String gueltigVon,
            @FormParam("gueltigBis") String gueltigBis) {
        try {
            LocalDate von = LocalDate.parse(gueltigVon);
            LocalDate bis = isNotBlank(gueltigBis) ? LocalDate.parse(gueltigBis) : null;
            personService.updateAdressGueltigkeit(personId, aid, von, bis);
            Adresse adresse = personService.findById(personId).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(aid))
                    .findFirst().orElseThrow();
            return adresseKarte.data("adresse", adresse).data("personId", personId);
        } catch (AdressUeberschneidungException e) {
            Adresse adresse = personService.findById(personId).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(aid))
                    .findFirst().orElseThrow();
            return adresseGueltigkeitForm
                    .data("adresse", adresse)
                    .data("personId", personId)
                    .data("errorMessage", escapeHtml(e.getMessage()));
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    /**
     * Updates personalien from form data and re-renders the personalien-form with success flag.
     */
    @PUT
    @Path("/fragments/{id}/personalien")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object updatePersonalienFragment(
            @PathParam("id") String personId,
            @FormParam("name") String name,
            @FormParam("vorname") String vorname,
            @FormParam("geschlecht") String geschlecht,
            @FormParam("geburtsdatum") String geburtsdatum) {
        try {
            personService.updatePersonalien(personId, name, vorname,
                    Geschlecht.valueOf(geschlecht),
                    LocalDate.parse(geburtsdatum));
            Person person = personService.findById(personId);
            return personalienForm.data("person", person).data("success", true);
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity("<p>" + escapeHtml(e.getMessage()) + "</p>").build();
        } catch (Exception e) {
            return Response.status(422)
                    .entity("<div class=\"alert alert-danger\">" + escapeHtml(e.getMessage()) + "</div>")
                    .build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

