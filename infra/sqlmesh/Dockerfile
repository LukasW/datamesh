FROM python:3.12-slim

RUN pip install --no-cache-dir "sqlmesh[trino]"

WORKDIR /sqlmesh
COPY infra/sqlmesh/config.yaml .

# Domain-owned silver models
COPY partner/data-product/sqlmesh/silver/  models/silver/partner/
COPY product/data-product/sqlmesh/silver/  models/silver/product/
COPY policy/data-product/sqlmesh/silver/   models/silver/policy/
COPY billing/data-product/sqlmesh/silver/  models/silver/billing/
COPY claims/data-product/sqlmesh/silver/   models/silver/claims/
COPY hr-system/data-product/sqlmesh/silver/ models/silver/hr/

# Domain-owned gold models
COPY partner/data-product/sqlmesh/gold/    models/gold/partner/
COPY policy/data-product/sqlmesh/gold/     models/gold/policy/
COPY billing/data-product/sqlmesh/gold/    models/gold/billing/
COPY claims/data-product/sqlmesh/gold/     models/gold/claims/
COPY hr-system/data-product/sqlmesh/gold/  models/gold/hr/

# Cross-domain analytics (central)
COPY infra/sqlmesh/models/gold/analytics/  models/gold/analytics/

# Domain-owned audits
COPY policy/data-product/sqlmesh/audits/   audits/

# Domain-owned tests
COPY hr-system/data-product/sqlmesh/tests/ models/tests/

ENTRYPOINT ["sqlmesh"]
CMD ["plan", "--auto-apply"]
