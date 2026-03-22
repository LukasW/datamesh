package ch.yuno.hr.web;

import ch.yuno.hr.model.Employee;
import ch.yuno.hr.model.OrganizationUnit;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Path("/mitarbeiter")
@Produces(MediaType.TEXT_HTML)
public class EmployeeUiController {

    @Inject
    EntityManager em;

    @Location("mitarbeiter/list")
    Template listTemplate;

    @Location("mitarbeiter/edit")
    Template editTemplate;

    @Location("mitarbeiter/fragments/mitarbeiter-rows")
    Template rowsTemplate;

    @Location("mitarbeiter/fragments/mitarbeiter-form-modal")
    Template formModalTemplate;

    @Location("mitarbeiter/fragments/personalien-form")
    Template personalienTemplate;

    @Location("mitarbeiter/fragments/zuordnung-form")
    Template zuordnungTemplate;

    @GET
    public TemplateInstance list() {
        var mitarbeiter = em.createQuery("SELECT e FROM Employee e ORDER BY e.lastName", Employee.class)
                .getResultList();
        return listTemplate.data("mitarbeiter", mitarbeiter);
    }

    @GET
    @Path("/fragments/list")
    public TemplateInstance listFragment(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("department") String department) {
        var mitarbeiter = searchEmployees(name, firstName, department);
        return rowsTemplate.data("mitarbeiter", mitarbeiter);
    }

    @GET
    @Path("/fragments/neu")
    public TemplateInstance createForm() {
        var orgUnits = allOrgUnits();
        return formModalTemplate.data("orgUnits", orgUnits);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance create(
            @FormParam("lastName") String lastName,
            @FormParam("firstName") String firstName,
            @FormParam("email") String email,
            @FormParam("jobTitle") String jobTitle,
            @FormParam("department") String department,
            @FormParam("orgUnitId") String orgUnitId,
            @FormParam("entryDate") String entryDate) {

        var emp = new Employee(UUID.randomUUID(), firstName, lastName, email,
                jobTitle, department,
                orgUnitId != null && !orgUnitId.isBlank() ? orgUnitId : null,
                LocalDate.parse(entryDate));
        em.persist(emp);

        var mitarbeiter = em.createQuery("SELECT e FROM Employee e ORDER BY e.lastName", Employee.class)
                .getResultList();
        return rowsTemplate.data("mitarbeiter", mitarbeiter);
    }

    @GET
    @Path("/{id}/edit")
    public TemplateInstance edit(@PathParam("id") UUID id) {
        var emp = em.find(Employee.class, id);
        var orgUnits = allOrgUnits();
        return editTemplate.data("emp", emp).data("orgUnits", orgUnits).data("success", false);
    }

    @PUT
    @Path("/{id}/personalien")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance updatePersonalien(
            @PathParam("id") UUID id,
            @FormParam("lastName") String lastName,
            @FormParam("firstName") String firstName,
            @FormParam("email") String email,
            @FormParam("entryDate") String entryDate,
            @FormParam("exitDate") String exitDate,
            @FormParam("active") String active) {

        var emp = em.find(Employee.class, id);
        emp.setLastName(lastName);
        emp.setFirstName(firstName);
        emp.setEmail(email);
        emp.setEntryDate(LocalDate.parse(entryDate));
        emp.setExitDate(exitDate != null && !exitDate.isBlank() ? LocalDate.parse(exitDate) : null);
        emp.setActive(active != null);
        emp.setLastModified(LocalDateTime.now());
        em.merge(emp);

        return personalienTemplate.data("emp", emp).data("success", true);
    }

    @PUT
    @Path("/{id}/zuordnung")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance updateZuordnung(
            @PathParam("id") UUID id,
            @FormParam("jobTitle") String jobTitle,
            @FormParam("department") String department,
            @FormParam("orgUnitId") String orgUnitId) {

        var emp = em.find(Employee.class, id);
        emp.setJobTitle(jobTitle);
        emp.setDepartment(department);
        emp.setOrgUnitId(orgUnitId != null && !orgUnitId.isBlank() ? orgUnitId : null);
        emp.setLastModified(LocalDateTime.now());
        em.merge(emp);

        var orgUnits = allOrgUnits();
        return zuordnungTemplate.data("emp", emp).data("orgUnits", orgUnits).data("success", true);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        var emp = em.find(Employee.class, id);
        if (emp != null) {
            em.remove(emp);
        }
        return Response.ok().build();
    }

    private List<Employee> searchEmployees(String name, String firstName, String department) {
        var query = new StringBuilder("SELECT e FROM Employee e WHERE 1=1");
        if (name != null && !name.isBlank()) {
            query.append(" AND LOWER(e.lastName) LIKE LOWER(:name)");
        }
        if (firstName != null && !firstName.isBlank()) {
            query.append(" AND LOWER(e.firstName) LIKE LOWER(:firstName)");
        }
        if (department != null && !department.isBlank()) {
            query.append(" AND LOWER(e.department) LIKE LOWER(:dept)");
        }
        query.append(" ORDER BY e.lastName");

        var q = em.createQuery(query.toString(), Employee.class);
        if (name != null && !name.isBlank()) q.setParameter("name", "%" + name + "%");
        if (firstName != null && !firstName.isBlank()) q.setParameter("firstName", "%" + firstName + "%");
        if (department != null && !department.isBlank()) q.setParameter("dept", "%" + department + "%");
        return q.getResultList();
    }

    private List<OrganizationUnit> allOrgUnits() {
        return em.createQuery("SELECT o FROM OrganizationUnit o ORDER BY o.name", OrganizationUnit.class)
                .getResultList();
    }
}
