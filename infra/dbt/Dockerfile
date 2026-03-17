FROM eclipse-temurin:17-jre-noble

RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java/openjdk

# Ubuntu 24.04 enforces PEP 668 – use a venv to avoid conflicts
RUN python3 -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH

# dbt-spark 1.7.x requires Python <3.13; pyspark/delta must match Spark 3.5
RUN pip install --no-cache-dir \
    "dbt-spark==1.7.0" \
    "pyspark==3.5.1" \
    "delta-spark==3.1.0"

WORKDIR /usr/app/dbt
COPY . .

ENV DBT_PROFILES_DIR=/usr/app/dbt
