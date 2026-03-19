FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir acryl-datahub[kafka]>=0.13 pyyaml>=6.0
COPY ingest_odc.py recipe_kafka.yaml ingest_compose.sh ./
RUN chmod +x ingest_compose.sh
CMD ["/app/ingest_compose.sh"]
