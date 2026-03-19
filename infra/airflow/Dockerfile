# infra/airflow/Dockerfile
FROM apache/airflow:2.9.3-python3.11

COPY --from=ghcr.io/astral-sh/uv:0.2.31 /uv /usr/local/bin/uv

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev git \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/requirements.txt
RUN uv pip install --system --no-cache-dir -r /tmp/requirements.txt

USER airflow
