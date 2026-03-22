package ch.yuno.hr.processor;

import ch.yuno.hr.ODataResponse;
import ch.yuno.hr.model.OrganizationUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;

/**
 * OData v4-compatible REST resource for OrganizationUnits.
 * Supports CRUD operations and delta queries via lastModified filter.
 */
@Path("/odata/OrganizationUnits")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrgUnitResource {

    @Inject
    EntityManager em;

    @GET
    public ODataResponse<OrganizationUnit> list(@QueryParam("$filter") String filter) {
        List<OrganizationUnit> units;
        if (filter != null && filter.contains("lastModified gt")) {
            var timestamp = extractTimestamp(filter);
            units = em.createQuery(
                    "SELECT o FROM OrganizationUnit o WHERE o.lastModified > :ts ORDER BY o.lastModified",
                    OrganizationUnit.class)
                .setParameter("ts", timestamp)
                .getResultList();
        } else {
            units = em.createQuery("SELECT o FROM OrganizationUnit o ORDER BY o.level, o.name",
                    OrganizationUnit.class)
                .getResultList();
        }
        return ODataResponse.of("OrganizationUnits", units);
    }

    @GET
    @Path("({id})")
    public Response findById(@PathParam("id") String id) {
        var unit = em.find(OrganizationUnit.class, id);
        if (unit == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(unit).build();
    }

    @POST
    @Transactional
    public Response create(OrganizationUnit unit) {
        unit.setLastModified(LocalDateTime.now());
        em.persist(unit);
        return Response.status(Response.Status.CREATED).entity(unit).build();
    }

    @PUT
    @Path("({id})")
    @Transactional
    public Response update(@PathParam("id") String id, OrganizationUnit updated) {
        var existing = em.find(OrganizationUnit.class, id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.setName(updated.getName());
        existing.setParentOrgUnitId(updated.getParentOrgUnitId());
        existing.setManagerEmployeeId(updated.getManagerEmployeeId());
        existing.setLevel(updated.getLevel());
        existing.setActive(updated.isActive());
        existing.setLastModified(LocalDateTime.now());
        em.merge(existing);
        return Response.ok(existing).build();
    }

    @DELETE
    @Path("({id})")
    @Transactional
    public Response delete(@PathParam("id") String id) {
        var existing = em.find(OrganizationUnit.class, id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        em.remove(existing);
        return Response.noContent().build();
    }

    private LocalDateTime extractTimestamp(String filter) {
        var parts = filter.split("lastModified gt ");
        if (parts.length > 1) {
            var ts = parts[1].trim().replace("'", "");
            // Strip trailing query params (e.g. ?bridgeEndpoint=true from Camel)
            int qmark = ts.indexOf('?');
            if (qmark > 0) ts = ts.substring(0, qmark);
            return LocalDateTime.parse(ts.replace("Z", ""));
        }
        return LocalDateTime.MIN;
    }
}
