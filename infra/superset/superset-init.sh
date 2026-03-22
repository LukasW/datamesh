#!/bin/bash
set -e

# Initialize Superset
superset db upgrade
superset fab create-admin \
  --username admin \
  --firstname Admin \
  --lastname User \
  --email admin@yuno.ch \
  --password admin 2>/dev/null || true
superset init

# Add Trino datasource
superset set-database-uri \
  --database-name "Iceberg (Trino)" \
  --uri "trino://trino@trino:8086/iceberg" 2>/dev/null || true

echo "Superset initialized successfully."
