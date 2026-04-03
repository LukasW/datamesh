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

# Provision Trino datasource, datasets, charts and overview dashboard
python3 <<'PYEOF'
import json
from superset.app import create_app

app = create_app()
with app.app_context():
    from superset.extensions import db
    from superset.models.core import Database
    from superset.connectors.sqla.models import SqlaTable
    from superset.models.slice import Slice
    from superset.models.dashboard import Dashboard

    # ── 1. Trino database connection ─────────────────────────────────────────
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
        trino_db = existing
        print("Trino datasource updated.")

    # ── 2. Virtual datasets (typed SQL views over raw Iceberg tables) ─────────
    DATASETS = [
        (
            "Policy Events",
            """SELECT
    eventtype                        AS event_type,
    policyid                         AS policy_id,
    coverageid                       AS coverage_id,
    coveragetype                     AS coverage_type,
    insuredamount                    AS insured_amount,
    CAST(timestamp AS TIMESTAMP)     AS event_time
FROM iceberg.policy_raw.policy_events""",
        ),
        (
            "Claims Events",
            """SELECT
    eventtype                        AS event_type,
    claimid                          AS claim_id,
    claimnumber                      AS claim_number,
    policyid                         AS policy_id,
    status,
    claimdate                        AS claim_date,
    CAST(timestamp AS TIMESTAMP)     AS event_time
FROM iceberg.claims_raw.claims_events""",
        ),
        (
            "Billing Events",
            """SELECT
    eventtype                        AS event_type,
    invoiceid                        AS invoice_id,
    invoicenumber                    AS invoice_number,
    policyid                         AS policy_id,
    CAST(totalamount AS DOUBLE)      AS amount_chf,
    CAST(timestamp AS TIMESTAMP)     AS event_time
FROM iceberg.billing_raw.billing_events""",
        ),
        (
            "Partner Events",
            """SELECT
    eventtype                        AS event_type,
    personid                         AS person_id,
    name,
    firstname                        AS first_name,
    insurednumber                    AS insured_number,
    CAST(timestamp AS TIMESTAMP)     AS event_time
FROM iceberg.partner_raw.person_events""",
        ),
        (
            "Product Events",
            """SELECT
    eventtype                        AS event_type,
    productid                        AS product_id,
    name                             AS product_name,
    productline                      AS product_line,
    basepremium                      AS base_premium,
    status,
    CAST(timestamp AS TIMESTAMP)     AS event_time
FROM iceberg.product_raw.product_events""",
        ),
    ]

    datasets = {}
    for name, sql in DATASETS:
        ds = db.session.query(SqlaTable).filter_by(
            table_name=name, database_id=trino_db.id
        ).first()
        if not ds:
            ds = SqlaTable(
                table_name=name,
                sql=sql,
                database_id=trino_db.id,
                schema=None,
                is_sqllab_view=False,
            )
            db.session.add(ds)
            db.session.flush()
            print(f"  Dataset '{name}' created.")
        else:
            ds.sql = sql
            print(f"  Dataset '{name}' already exists.")
        datasets[name] = ds

    db.session.commit()

    # ── 3. Charts ─────────────────────────────────────────────────────────────
    def count_metric():
        return {
            "expressionType": "SQL",
            "sqlExpression": "COUNT(*)",
            "label": "COUNT(*)",
            "hasCustomLabel": False,
            "optionName": "metric_count",
        }

    def sum_metric(col, label):
        return {
            "expressionType": "SQL",
            "sqlExpression": f"SUM({col})",
            "label": label,
            "hasCustomLabel": True,
            "optionName": f"metric_sum_{col}",
        }

    def get_or_create_chart(name, viz_type, dataset, extra_params):
        params = {
            "viz_type": viz_type,
            "datasource": f"{dataset.id}__table",
            "time_range": "No filter",
        }
        params.update(extra_params)
        chart = db.session.query(Slice).filter_by(slice_name=name).first()
        if chart:
            chart.params = json.dumps(params)
            chart.viz_type = viz_type
            chart.datasource_id = dataset.id
            print(f"  Chart '{name}' updated.")
        else:
            chart = Slice(
                slice_name=name,
                viz_type=viz_type,
                datasource_id=dataset.id,
                datasource_type="table",
                params=json.dumps(params),
            )
            db.session.add(chart)
            print(f"  Chart '{name}' created.")
        db.session.flush()
        return chart

    ds_policy  = datasets["Policy Events"]
    ds_claims  = datasets["Claims Events"]
    ds_billing = datasets["Billing Events"]
    ds_partner = datasets["Partner Events"]
    ds_product = datasets["Product Events"]

    chart_policy_pie = get_or_create_chart(
        "Policen nach Ereignistyp", "pie", ds_policy,
        {"metric": count_metric(), "groupby": ["event_type"], "row_limit": 50},
    )
    chart_claims_pie = get_or_create_chart(
        "Schäden nach Ereignistyp", "pie", ds_claims,
        {"metric": count_metric(), "groupby": ["event_type"], "row_limit": 50},
    )
    chart_billing_bar = get_or_create_chart(
        "Abrechnung – Events nach Typ", "table", ds_billing,
        {
            "metrics": [count_metric()],
            "groupby": ["event_type"],
            "row_limit": 50,
            "include_time": False,
        },
    )
    chart_settlement = get_or_create_chart(
        "Schadenmeldungen Total", "big_number_total", ds_claims,
        {
            "metric": count_metric(),
            "subheader": "Claims Events in Iceberg",
        },
    )
    chart_billing_total = get_or_create_chart(
        "Rechnungen Total", "big_number_total", ds_billing,
        {"metric": count_metric(), "subheader": "Billing Events in Iceberg"},
    )
    chart_partner_table = get_or_create_chart(
        "Partner Event-Übersicht", "table", ds_partner,
        {
            "metrics": [count_metric()],
            "groupby": ["event_type"],
            "row_limit": 100,
            "include_time": False,
        },
    )
    chart_product_table = get_or_create_chart(
        "Produkte – aktuelle Events", "table", ds_product,
        {
            "metrics": [count_metric()],
            "groupby": ["event_type", "product_name"],
            "row_limit": 100,
            "include_time": False,
        },
    )

    db.session.commit()

    # ── 4. Dashboard ──────────────────────────────────────────────────────────
    DASH_TITLE = "Datamesh Platform – Übersicht"
    dash = db.session.query(Dashboard).filter_by(dashboard_title=DASH_TITLE).first()

    all_charts = [
        chart_policy_pie,
        chart_claims_pie,
        chart_billing_bar,
        chart_settlement,
        chart_billing_total,
        chart_partner_table,
        chart_product_table,
    ]

    def chart_pos(chart, row, width, height=50):
        cid = f"CHART_{chart.id}"
        return cid, {
            "type": "CHART",
            "id": cid,
            "children": [],
            "meta": {
                "chartId": chart.id,
                "height": height,
                "sliceName": chart.slice_name,
                "width": width,
            },
        }

    # Layout: Row1: [policy_pie(6), claims_pie(6)]
    #         Row2: [billing_bar(12)]
    #         Row3: [settlement(4), billing_total(4), partner_table(4)]
    #         Row4: [product_table(12)]
    c1_id, c1 = chart_pos(chart_policy_pie,   "ROW_1", 6)
    c2_id, c2 = chart_pos(chart_claims_pie,   "ROW_1", 6)
    c3_id, c3 = chart_pos(chart_billing_bar,  "ROW_2", 12)
    c4_id, c4 = chart_pos(chart_settlement,   "ROW_3", 4, height=34)
    c5_id, c5 = chart_pos(chart_billing_total,"ROW_3", 4, height=34)
    c6_id, c6 = chart_pos(chart_partner_table,"ROW_3", 4, height=34)
    c7_id, c7 = chart_pos(chart_product_table,"ROW_4", 12)

    positions = {
        "DASHBOARD_VERSION_KEY": "v2",
        "ROOT_ID":  {"type": "ROOT", "id": "ROOT_ID", "children": ["GRID_ID"]},
        "GRID_ID":  {"type": "GRID", "id": "GRID_ID", "children": ["ROW_1", "ROW_2", "ROW_3", "ROW_4"]},
        "ROW_1": {"type": "ROW", "id": "ROW_1", "children": [c1_id, c2_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_2": {"type": "ROW", "id": "ROW_2", "children": [c3_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_3": {"type": "ROW", "id": "ROW_3", "children": [c4_id, c5_id, c6_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_4": {"type": "ROW", "id": "ROW_4", "children": [c7_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        c1_id: c1, c2_id: c2, c3_id: c3,
        c4_id: c4, c5_id: c5, c6_id: c6, c7_id: c7,
    }

    if not dash:
        dash = Dashboard(
            dashboard_title=DASH_TITLE,
            slug="datamesh-overview",
            slices=all_charts,
            position_json=json.dumps(positions),
            published=True,
        )
        db.session.add(dash)
        db.session.commit()
        print(f"Dashboard '{DASH_TITLE}' created (slug: datamesh-overview).")
    else:
        dash.slices = all_charts
        dash.position_json = json.dumps(positions)
        dash.published = True
        db.session.commit()
        print(f"Dashboard '{DASH_TITLE}' updated.")

PYEOF

echo "Superset initialized successfully."
