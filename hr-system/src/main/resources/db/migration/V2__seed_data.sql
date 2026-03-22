-- Organizational structure
INSERT INTO organization_unit (org_unit_id, name, parent_org_unit_id, manager_employee_id, level, active, version, last_modified)
VALUES ('OU-ROOT', 'Yuno Versicherung AG', NULL, NULL, 1, true, 1, now()),
       ('OU-VER-001', 'Versicherungsbetrieb', 'OU-ROOT', NULL, 2, true, 1, now()),
       ('OU-POL-001', 'Policenverwaltung', 'OU-VER-001', NULL, 3, true, 1, now()),
       ('OU-CLM-001', 'Schadenabwicklung', 'OU-VER-001', NULL, 3, true, 1, now()),
       ('OU-BIL-001', 'Inkasso/Exkasso', 'OU-VER-001', NULL, 3, true, 1, now()),
       ('OU-SAL-001', 'Vertrieb', 'OU-ROOT', NULL, 2, true, 1, now()),
       ('OU-IT-001', 'IT & Daten', 'OU-ROOT', NULL, 2, true, 1, now());

-- Employees
INSERT INTO employee (employee_id, first_name, last_name, email, job_title, department, org_unit_id, entry_date, active, version, last_modified)
VALUES ('a1b2c3d4-0001-0001-0001-000000000001', 'Anna', 'Meier', 'anna.meier@yuno.ch', 'Schadensachbearbeiterin', 'Claims', 'OU-CLM-001', '2023-06-01', true, 1, now()),
       ('a1b2c3d4-0001-0001-0001-000000000002', 'Peter', 'Brunner', 'peter.brunner@yuno.ch', 'Underwriter', 'Policy', 'OU-POL-001', '2021-03-15', true, 1, now()),
       ('a1b2c3d4-0001-0001-0001-000000000003', 'Lisa', 'Hofmann', 'lisa.hofmann@yuno.ch', 'Teamleiterin Billing', 'Billing', 'OU-BIL-001', '2019-01-10', true, 1, now()),
       ('a1b2c3d4-0001-0001-0001-000000000004', 'Marco', 'Keller', 'marco.keller@yuno.ch', 'Data Engineer', 'IT', 'OU-IT-001', '2024-09-01', true, 1, now());
