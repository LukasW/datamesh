FROM python:3.12-slim

RUN pip install --no-cache-dir setuptools soda-core-trino

WORKDIR /soda
COPY infra/soda/configuration.yml .

# Domain-owned Soda checks
COPY partner/data-product/soda/checks.yml   checks/partner.yml
COPY product/data-product/soda/checks.yml   checks/product.yml
COPY policy/data-product/soda/checks.yml    checks/policy.yml
COPY billing/data-product/soda/checks.yml   checks/billing.yml
COPY hr-system/data-product/soda/checks.yml checks/hr.yml

ENTRYPOINT ["soda"]
CMD ["scan", "-d", "iceberg", "-c", "configuration.yml", "checks/"]
