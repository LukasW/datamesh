#!/bin/sh
set -e

# Wait for MinIO to be ready
until mc alias set local http://minio:9000 minioadmin minioadmin 2>/dev/null; do
  echo "Waiting for MinIO..."
  sleep 2
done

# Create the warehouse bucket
mc mb local/warehouse --ignore-existing
echo "MinIO bucket 'warehouse' ready."
