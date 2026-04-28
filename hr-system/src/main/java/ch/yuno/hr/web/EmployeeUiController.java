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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Path("/mitarbeiter")
@Produces(MediaType.TEXT_HTML)
public class EmployeeUiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Inject
    EntityManager em;

    @Location("mitarbeiter/list")
    Template listTemplate;

    @Location("mitarbeiter/edit")
    Template editTemplate;

    @Location("mitarbeiter/fragments/mitarbeiter-form-modal")
    Template formModalTemplate;

    @Location("mitarbeiter/fragments/personalien-form")
    Template personalienTemplate;

    @Location("mitarbeiter/fragments/zuordnung-form")
    Template zuordnungTemplate;

    @GET
    public TemplateInstance list(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("department") String department,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return renderListPage(listTemplate, name, firstName, department, page, size);
    }

    @GET
    @Path("/fragments/list")
    public TemplateInstance listFragment(
            @QueryParam("name") String name,
            @QueryParam("firstName") String firstName,
            @QueryParam("department") String department,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return renderListPage(listTemplate.getFragment("tabelle"), name, firstName, department, page, size);
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

        return renderListPage(listTemplate.getFragment("tabelle"), null, null, null, 0, DEFAULT_PAGE_SIZE);
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

    public record PageLink(int index, int label, boolean active) {}

    private TemplateInstance renderListPage(Template template,
                                             String name, String firstName, String department,
                                             int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size > 0 ? size : DEFAULT_PAGE_SIZE;

        long total = countEmployees(name, firstName, department);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        if (totalPages > 0 && safePage >= totalPages) {
            safePage = totalPages - 1;
        }
        var employees = searchEmployees(name, firstName, department, safePage, safeSize);

        var pageLinks = new java.util.ArrayList<PageLink>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            pageLinks.add(new PageLink(i, i + 1, i == safePage));
        }
        int prevPage = Math.max(0, safePage - 1);
        int nextPage = totalPages == 0 ? 0 : Math.min(totalPages - 1, safePage + 1);

        return template.instance()
                .data("mitarbeiter", employees)
                .data("currentPage", safePage)
                .data("currentPageLabel", safePage + 1)
                .data("totalPages", totalPages)
                .data("totalElements", total)
                .data("pageSize", safeSize)
                .data("pageLinks", pageLinks)
                .data("prevPage", prevPage)
                .data("nextPage", nextPage)
                .data("isFirstPage", safePage == 0)
                .data("isLastPage", totalPages == 0 || safePage >= totalPages - 1)
                .data("searchName", name != null ? name : "")
                .data("searchFirstName", firstName != null ? firstName : "")
                .data("searchDepartment", department != null ? department : "");
    }

    private List<Employee> searchEmployees(String name, String firstName, String department,
                                           int page, int size) {
        var jpql = new StringBuilder("SELECT e FROM Employee e WHERE 1=1");
        appendFilters(jpql, name, firstName, department);
        jpql.append(" ORDER BY e.lastName");

        var q = em.createQuery(jpql.toString(), Employee.class);
        applyFilters(q, name, firstName, department);
        q.setFirstResult(page * size);
        q.setMaxResults(size);
        return q.getResultList();
    }

    private long countEmployees(String name, String firstName, String department) {
        var jpql = new StringBuilder("SELECT COUNT(e) FROM Employee e WHERE 1=1");
        appendFilters(jpql, name, firstName, department);

        var q = em.createQuery(jpql.toString(), Long.class);
        applyFilters(q, name, firstName, department);
        return q.getSingleResult();
    }

    private static void appendFilters(StringBuilder jpql, String name, String firstName, String department) {
        if (name != null && !name.isBlank()) {
            jpql.append(" AND LOWER(e.lastName) LIKE LOWER(:name)");
        }
        if (firstName != null && !firstName.isBlank()) {
            jpql.append(" AND LOWER(e.firstName) LIKE LOWER(:firstName)");
        }
        if (department != null && !department.isBlank()) {
            jpql.append(" AND LOWER(e.department) LIKE LOWER(:dept)");
        }
    }

    private static void applyFilters(jakarta.persistence.Query q,
                                     String name, String firstName, String department) {
        if (name != null && !name.isBlank()) q.setParameter("name", "%" + name + "%");
        if (firstName != null && !firstName.isBlank()) q.setParameter("firstName", "%" + firstName + "%");
        if (department != null && !department.isBlank()) q.setParameter("dept", "%" + department + "%");
    }

    private List<OrganizationUnit> allOrgUnits() {
        return em.createQuery("SELECT o FROM OrganizationUnit o ORDER BY o.name", OrganizationUnit.class)
                .getResultList();
    }
}
