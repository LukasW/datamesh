package ch.yuno.hr.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "organization_unit")
public class OrganizationUnit {

    @Id
    @Column(name = "org_unit_id")
    private String orgUnitId;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_org_unit_id")
    private String parentOrgUnitId;

    @Column(name = "manager_employee_id")
    private UUID managerEmployeeId;

    private int level = 1;

    private boolean active = true;

    @Version
    private long version;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    public OrganizationUnit() {}

    public OrganizationUnit(String orgUnitId, String name, String parentOrgUnitId, int level) {
        this.orgUnitId = orgUnitId;
        this.name = name;
        this.parentOrgUnitId = parentOrgUnitId;
        this.level = level;
        this.lastModified = LocalDateTime.now();
    }

    public String getOrgUnitId() { return orgUnitId; }
    public void setOrgUnitId(String orgUnitId) { this.orgUnitId = orgUnitId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParentOrgUnitId() { return parentOrgUnitId; }
    public void setParentOrgUnitId(String parentOrgUnitId) { this.parentOrgUnitId = parentOrgUnitId; }
    public UUID getManagerEmployeeId() { return managerEmployeeId; }
    public void setManagerEmployeeId(UUID managerEmployeeId) { this.managerEmployeeId = managerEmployeeId; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public LocalDateTime getLastModified() { return lastModified; }
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }
}
