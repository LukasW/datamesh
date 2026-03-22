import os

SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "datamesh-dev-secret-change-in-prod")

SQLALCHEMY_DATABASE_URI = "postgresql+psycopg2://superset:superset@superset-db:5432/superset"

# Trino datasource (auto-configured)
SQLALCHEMY_CUSTOM_PASSWORD_STORE = None

# Authentication: AUTH_DB=1, AUTH_LDAP=2, AUTH_REMOTE_USER=3, AUTH_OAUTH=4
# Use local database auth for dev; switch to AUTH_OAUTH + Keycloak for prod.
AUTH_TYPE = 1  # AUTH_DB

# Row-Level Security enabled
FEATURE_FLAGS = {
    "ROW_LEVEL_SECURITY": True,
    "EMBEDDED_SUPERSET": True,
}

# Cache (in-memory for dev)
CACHE_CONFIG = {
    "CACHE_TYPE": "SimpleCache",
    "CACHE_DEFAULT_TIMEOUT": 300,
}
