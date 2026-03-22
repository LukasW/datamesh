FROM python:3.12-slim

RUN pip install --no-cache-dir soda-core-trino

WORKDIR /soda
COPY configuration.yml .
COPY checks/ checks/

ENTRYPOINT ["soda"]
CMD ["scan", "-d", "iceberg", "-c", "configuration.yml", "checks/"]
