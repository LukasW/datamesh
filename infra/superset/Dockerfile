FROM apache/superset

# Install PostgreSQL and Trino drivers into the venv as root (venv is root-owned)
USER root
RUN /app/docker/pip-install.sh psycopg2-binary sqlalchemy-trino

# Switch back to the default superset user
USER superset

