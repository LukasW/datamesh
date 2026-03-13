package ch.css.partner.infrastructure.web;

import ch.css.partner.domain.model.Adresse;
import ch.css.partner.domain.model.AdressTyp;
import ch.css.partner.domain.model.Geschlecht;
import ch.css.partner.domain.model.Person;
import ch.css.partner.domain.service.AdressUeberschneidungException;
import ch.css.partner.domain.service.AdresseNotFoundException;
import ch.css.partner.domain.service.PersonApplicationService;
import ch.css.partner.domain.service.PersonNotFoundException;
import ch.css.partner.infrastructure.persistence.PersonAuditAdapter;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST adapter for Person CRUD and Address sub-resource.
 * Maps HTTP requests to PersonApplicationService use cases.
 */
@Path("/api/personen")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonRestAdapter {

    @Inject
    PersonApplicationService personService;

    @Inject
    PersonAuditAdapter auditAdapter;

    // ── Person CRUD ───────────────────────────────────────────────────────────

    @POST
    public Response createPerson(CreatePersonRequest req) {
        try {
            String id = personService.createPerson(
                    req.name(), req.vorname(),
                    Geschlecht.valueOf(req.geschlecht()),
                    req.geburtsdatum(), req.ahvNummer());
            return Response.status(201).entity(Map.of("id", id)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    public Response searchPersonen(
            @QueryParam("name") String name,
            @QueryParam("vorname") String vorname,
            @QueryParam("ahv") String ahv,
            @QueryParam("geburtsdatum") String geburtsdatum) {
        boolean hasFilter = isNotBlank(name) || isNotBlank(vorname)
                || isNotBlank(ahv) || isNotBlank(geburtsdatum);
        if (!hasFilter) {
            return Response.ok(List.of()).build();
        }
        try {
            LocalDate geb = isNotBlank(geburtsdatum) ? LocalDate.parse(geburtsdatum) : null;
            List<PersonDto> result = personService.searchPersonen(name, vorname, ahv, geb)
                    .stream().map(PersonDto::from).toList();
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{id}")
    public Response getPerson(@PathParam("id") String id) {
        try {
            return Response.ok(PersonDto.from(personService.findById(id))).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}")
    public Response updatePerson(@PathParam("id") String id, UpdatePersonRequest req) {
        try {
            personService.updatePersonalien(id, req.name(), req.vorname(),
                    Geschlecht.valueOf(req.geschlecht()), req.geburtsdatum());
            return Response.ok(PersonDto.from(personService.findById(id))).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response deletePerson(@PathParam("id") String id) {
        try {
            personService.deletePerson(id);
            return Response.noContent().build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Adressen ──────────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/adressen")
    public Response getAdressen(@PathParam("id") String id,
                                 @QueryParam("typ") String typ,
                                 @QueryParam("aktuell") Boolean aktuell) {
        try {
            List<Adresse> adressen = personService.getAdressen(id);
            if (isNotBlank(typ)) {
                AdressTyp adressTyp = AdressTyp.valueOf(typ);
                adressen = adressen.stream().filter(a -> a.getAdressTyp() == adressTyp).toList();
            }
            if (Boolean.TRUE.equals(aktuell)) {
                adressen = adressen.stream().filter(Adresse::isAktuell).toList();
            }
            return Response.ok(adressen.stream().map(AdresseDto::from).toList()).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/adressen")
    public Response addAdresse(@PathParam("id") String id, AddAdresseRequest req) {
        try {
            String adressId = personService.addAdresse(
                    id,
                    AdressTyp.valueOf(req.adressTyp()),
                    req.strasse(), req.hausnummer(), req.plz(), req.ort(),
                    req.land() != null ? req.land() : "Schweiz",
                    req.gueltigVon(), req.gueltigBis());
            Adresse adresse = personService.findById(id).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(adressId))
                    .findFirst().orElseThrow();
            return Response.status(201).entity(AdresseDto.from(adresse)).build();
        } catch (AdressUeberschneidungException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        } catch (PersonNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{id}/adressen/{aid}")
    public Response updateAdresseGueltigkeit(@PathParam("id") String id,
                                              @PathParam("aid") String aid,
                                              UpdateAdresseRequest req) {
        try {
            personService.updateAdressGueltigkeit(id, aid, req.gueltigVon(), req.gueltigBis());
            Adresse adresse = personService.findById(id).getAdressen().stream()
                    .filter(a -> a.getAdressId().equals(aid))
                    .findFirst().orElseThrow();
            return Response.ok(AdresseDto.from(adresse)).build();
        } catch (AdressUeberschneidungException e) {
            return Response.status(409).entity(Map.of("message", e.getMessage())).build();
        } catch (PersonNotFoundException | AdresseNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("message", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{id}/adressen/{aid}")
    public Response deleteAdresse(@PathParam("id") String id, @PathParam("aid") String aid) {
        try {
            personService.deleteAdresse(id, aid);
            return Response.noContent().build();
        } catch (PersonNotFoundException | AdresseNotFoundException e) {
            return Response.status(404).entity(Map.of("message", e.getMessage())).build();
        }
    }

    // ── Audit History ─────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/history")
    public Response getPersonHistory(@PathParam("id") String id) {
        return Response.ok(auditAdapter.getPersonHistory(id)).build();
    }

    @GET
    @Path("/{id}/adressen/{aid}/history")
    public Response getAdresseHistory(@PathParam("id") String id, @PathParam("aid") String aid) {
        return Response.ok(auditAdapter.getAdresseHistory(aid)).build();
    }

    // ── Request/Response DTOs ─────────────────────────────────────────────────

    public record CreatePersonRequest(
            String name, String vorname, String geschlecht,
            LocalDate geburtsdatum, String ahvNummer) {}

    public record UpdatePersonRequest(
            String name, String vorname, String geschlecht, LocalDate geburtsdatum) {}

    public record AddAdresseRequest(
            String adressTyp, String strasse, String hausnummer,
            String plz, String ort, String land,
            LocalDate gueltigVon, LocalDate gueltigBis) {}

    public record UpdateAdresseRequest(LocalDate gueltigVon, LocalDate gueltigBis) {}

    public record PersonDto(
            String personId, String name, String vorname, String geschlecht,
            LocalDate geburtsdatum, String ahvNummer, List<AdresseDto> adressen) {

        public static PersonDto from(Person p) {
            return new PersonDto(
                    p.getPersonId(), p.getName(), p.getVorname(),
                    p.getGeschlecht().name(), p.getGeburtsdatum(),
                    p.getAhvNummer() != null ? p.getAhvNummer().formatted() : null,
                    p.getAdressen().stream().map(AdresseDto::from).toList());
        }
    }

    public record AdresseDto(
            String adressId, String personId, String adressTyp,
            String strasse, String hausnummer, String plz, String ort, String land,
            LocalDate gueltigVon, LocalDate gueltigBis, boolean aktuell) {

        public static AdresseDto from(Adresse a) {
            return new AdresseDto(
                    a.getAdressId(), a.getPersonId(), a.getAdressTyp().name(),
                    a.getStrasse(), a.getHausnummer(), a.getPlz(), a.getOrt(), a.getLand(),
                    a.getGueltigVon(), a.getGueltigBis(), a.isAktuell());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}

