import os

SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "datamesh-dev-secret-change-in-prod")

SQLALCHEMY_DATABASE_URI = "postgresql+psycopg2://superset:superset@superset-db:5432/superset"

# Trino datasource (auto-configured)
SQLALCHEMY_CUSTOM_PASSWORD_STORE = None

# Keycloak OIDC SSO
AUTH_TYPE = 2  # AUTH_OID
OIDC_CLIENT_ID = "superset"
OIDC_CLIENT_SECRET = os.environ.get("OIDC_CLIENT_SECRET", "secret")
OIDC_OPENID_REALM = "css"

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
