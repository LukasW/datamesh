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

# Add Trino datasource with full schema discovery
python3 <<'PYEOF'
import json
from superset.app import create_app

app = create_app()
with app.app_context():
    from superset.extensions import db
    from superset.models.core import Database

    extra = json.dumps({
        "allows_virtual_table_explore": True,
        "metadata_params": {},
        "engine_params": {},
        "schemas_allowed_for_file_upload": [],
        "allow_multi_schema_metadata_fetch": True,
    })

    existing = db.session.query(Database).filter_by(database_name="Iceberg (Trino)").first()
    if not existing:
        trino_db = Database(
            database_name="Iceberg (Trino)",
            sqlalchemy_uri="trino://trino@trino:8086/iceberg",
            expose_in_sqllab=True,
            extra=extra,
        )
        db.session.add(trino_db)
        db.session.commit()
        print("Trino datasource created.")
    else:
        existing.expose_in_sqllab = True
        existing.extra = extra
        db.session.commit()
        print("Trino datasource updated.")
PYEOF

echo "Superset initialized successfully."
