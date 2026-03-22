-- Create separate database for Airflow (OpenMetadata Ingestion)
CREATE DATABASE airflow;
GRANT ALL PRIVILEGES ON DATABASE airflow TO openmetadata;
