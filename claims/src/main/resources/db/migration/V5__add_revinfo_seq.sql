-- V5: Add revinfo_seq alias required by Hibernate Envers 6 (which uses revinfo_seq
--     as the default sequence name, but V2 used SERIAL which created revinfo_rev_seq).
CREATE SEQUENCE IF NOT EXISTS revinfo_seq
    START WITH 1
    INCREMENT BY 50
    NO CYCLE;
