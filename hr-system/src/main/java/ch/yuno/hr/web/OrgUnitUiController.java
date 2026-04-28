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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Path("/organisation")
@Produces(MediaType.TEXT_HTML)
public class OrgUnitUiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Inject
    EntityManager em;

    @Location("organisation/list")
    Template listTemplate;

    @Location("organisation/edit")
    Template editTemplate;

    @Location("organisation/fragments/org-form-modal")
    Template formModalTemplate;

    @Location("organisation/fragments/org-edit-form")
    Template editFormTemplate;

    @GET
    public TemplateInstance list(
            @QueryParam("name") String name,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return renderListPage(listTemplate, name, page, size);
    }

    @GET
    @Path("/fragments/list")
    public TemplateInstance listFragment(
            @QueryParam("name") String name,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return renderListPage(listTemplate.getFragment("tabelle"), name, page, size);
    }

    @GET
    @Path("/fragments/neu")
    public TemplateInstance createForm() {
        return formModalTemplate
                .data("allOrgUnits", allOrgUnits())
                .data("employees", allEmployees());
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance create(
            @FormParam("orgUnitId") String orgUnitId,
            @FormParam("name") String name,
            @FormParam("parentOrgUnitId") String parentOrgUnitId,
            @FormParam("level") int level,
            @FormParam("managerEmployeeId") String managerEmployeeId) {

        var ou = new OrganizationUnit(orgUnitId, name,
                parentOrgUnitId != null && !parentOrgUnitId.isBlank() ? parentOrgUnitId : null,
                level);
        if (managerEmployeeId != null && !managerEmployeeId.isBlank()) {
            ou.setManagerEmployeeId(UUID.fromString(managerEmployeeId));
        }
        ou.setLastModified(LocalDateTime.now());
        em.persist(ou);

        return renderListPage(listTemplate.getFragment("tabelle"), null, 0, DEFAULT_PAGE_SIZE);
    }

    @GET
    @Path("/{id}/edit")
    public TemplateInstance edit(@PathParam("id") String id) {
        var ou = em.find(OrganizationUnit.class, id);
        return editTemplate
                .data("ou", ou)
                .data("allOrgUnits", allOrgUnits())
                .data("employees", allEmployees());
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public TemplateInstance update(
            @PathParam("id") String id,
            @FormParam("name") String name,
            @FormParam("parentOrgUnitId") String parentOrgUnitId,
            @FormParam("level") int level,
            @FormParam("managerEmployeeId") String managerEmployeeId,
            @FormParam("active") String active) {

        var ou = em.find(OrganizationUnit.class, id);
        ou.setName(name);
        ou.setParentOrgUnitId(parentOrgUnitId != null && !parentOrgUnitId.isBlank() ? parentOrgUnitId : null);
        ou.setLevel(level);
        ou.setManagerEmployeeId(managerEmployeeId != null && !managerEmployeeId.isBlank()
                ? UUID.fromString(managerEmployeeId) : null);
        ou.setActive(active != null);
        ou.setLastModified(LocalDateTime.now());
        em.merge(ou);

        return editFormTemplate
                .data("ou", ou)
                .data("allOrgUnits", allOrgUnits())
                .data("employees", allEmployees())
                .data("success", true);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") String id) {
        var ou = em.find(OrganizationUnit.class, id);
        if (ou != null) {
            em.remove(ou);
        }
        return Response.ok().build();
    }

    private TemplateInstance renderListPage(Template template, String name, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = size > 0 ? size : DEFAULT_PAGE_SIZE;

        long total = countOrgUnits(name);
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        if (totalPages > 0 && safePage >= totalPages) {
            safePage = totalPages - 1;
        }
        var orgUnits = searchOrgUnits(name, safePage, safeSize);

        return template.instance()
                .data("orgUnits", orgUnits)
                .data("currentPage", safePage)
                .data("totalPages", totalPages)
                .data("totalElements", total)
                .data("pageSize", safeSize)
                .data("searchName", name != null ? name : "");
    }

    private List<OrganizationUnit> searchOrgUnits(String name, int page, int size) {
        var jpql = new StringBuilder("SELECT o FROM OrganizationUnit o WHERE 1=1");
        if (name != null && !name.isBlank()) {
            jpql.append(" AND LOWER(o.name) LIKE LOWER(:name)");
        }
        jpql.append(" ORDER BY o.level, o.name");

        var q = em.createQuery(jpql.toString(), OrganizationUnit.class);
        if (name != null && !name.isBlank()) {
            q.setParameter("name", "%" + name + "%");
        }
        q.setFirstResult(page * size);
        q.setMaxResults(size);
        return q.getResultList();
    }

    private long countOrgUnits(String name) {
        var jpql = new StringBuilder("SELECT COUNT(o) FROM OrganizationUnit o WHERE 1=1");
        if (name != null && !name.isBlank()) {
            jpql.append(" AND LOWER(o.name) LIKE LOWER(:name)");
        }
        var q = em.createQuery(jpql.toString(), Long.class);
        if (name != null && !name.isBlank()) {
            q.setParameter("name", "%" + name + "%");
        }
        return q.getSingleResult();
    }

    private List<OrganizationUnit> allOrgUnits() {
        return em.createQuery("SELECT o FROM OrganizationUnit o ORDER BY o.level, o.name", OrganizationUnit.class)
                .getResultList();
    }

    private List<Employee> allEmployees() {
        return em.createQuery("SELECT e FROM Employee e WHERE e.active = true ORDER BY e.lastName", Employee.class)
                .getResultList();
    }
}
