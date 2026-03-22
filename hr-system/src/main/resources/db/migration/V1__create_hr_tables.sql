CREATE TABLE organization_unit
(
    org_unit_id         VARCHAR(50)  NOT NULL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    parent_org_unit_id  VARCHAR(50),
    manager_employee_id UUID,
    level               INT          NOT NULL DEFAULT 1,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    version             BIGINT       NOT NULL DEFAULT 1,
    last_modified       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT fk_parent FOREIGN KEY (parent_org_unit_id) REFERENCES organization_unit (org_unit_id)
);

CREATE TABLE employee
(
    employee_id   UUID         NOT NULL PRIMARY KEY,
    first_name    VARCHAR(255) NOT NULL,
    last_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(255),
    job_title     VARCHAR(255),
    department    VARCHAR(255),
    org_unit_id   VARCHAR(50),
    entry_date    DATE         NOT NULL,
    exit_date     DATE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 1,
    last_modified TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT fk_org_unit FOREIGN KEY (org_unit_id) REFERENCES organization_unit (org_unit_id)
);
