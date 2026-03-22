package ch.yuno.hr.processor;

import ch.yuno.hr.ODataResponse;
import ch.yuno.hr.model.Employee;
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
import java.util.UUID;

/**
 * OData v4-compatible REST resource for Employees.
 * Supports CRUD operations and delta queries via lastModified filter.
 */
@Path("/odata/Employees")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmployeeResource {

    @Inject
    EntityManager em;

    @GET
    public ODataResponse<Employee> list(@QueryParam("$filter") String filter) {
        List<Employee> employees;
        if (filter != null && filter.contains("lastModified gt")) {
            var timestamp = extractTimestamp(filter);
            employees = em.createQuery(
                    "SELECT e FROM Employee e WHERE e.lastModified > :ts ORDER BY e.lastModified",
                    Employee.class)
                .setParameter("ts", timestamp)
                .getResultList();
        } else {
            employees = em.createQuery("SELECT e FROM Employee e ORDER BY e.lastName", Employee.class)
                .getResultList();
        }
        return ODataResponse.of("Employees", employees);
    }

    @GET
    @Path("({id})")
    public Response findById(@PathParam("id") UUID id) {
        var employee = em.find(Employee.class, id);
        if (employee == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(employee).build();
    }

    @POST
    @Transactional
    public Response create(Employee employee) {
        if (employee.getEmployeeId() == null) {
            employee.setEmployeeId(UUID.randomUUID());
        }
        employee.setLastModified(LocalDateTime.now());
        em.persist(employee);
        return Response.status(Response.Status.CREATED).entity(employee).build();
    }

    @PUT
    @Path("({id})")
    @Transactional
    public Response update(@PathParam("id") UUID id, Employee updated) {
        var existing = em.find(Employee.class, id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.setFirstName(updated.getFirstName());
        existing.setLastName(updated.getLastName());
        existing.setEmail(updated.getEmail());
        existing.setJobTitle(updated.getJobTitle());
        existing.setDepartment(updated.getDepartment());
        existing.setOrgUnitId(updated.getOrgUnitId());
        existing.setEntryDate(updated.getEntryDate());
        existing.setExitDate(updated.getExitDate());
        existing.setActive(updated.isActive());
        existing.setLastModified(LocalDateTime.now());
        em.merge(existing);
        return Response.ok(existing).build();
    }

    @DELETE
    @Path("({id})")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        var existing = em.find(Employee.class, id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        em.remove(existing);
        return Response.noContent().build();
    }

    private LocalDateTime extractTimestamp(String filter) {
        // Parses: "lastModified gt 2026-03-22T14:30:00Z"
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
