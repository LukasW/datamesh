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

    # ── 2. Virtual datasets (Silver/Gold Iceberg tables) ─────────────────────
    DATASETS = [
        (
            "Policy Events",
            """SELECT
    policy_id,
    policy_number,
    partner_id,
    product_id,
    coverage_start_date,
    premium_chf,
    policy_status,
    issued_at,
    updated_at
FROM iceberg.policy_silver.policy""",
        ),
        (
            "Claims Events",
            """SELECT
    claim_id,
    claim_number,
    policy_id,
    description,
    claim_date,
    status,
    opened_at,
    updated_at
FROM iceberg.claims_silver.claim""",
        ),
        (
            "Billing Events",
            """SELECT
    invoice_id,
    invoice_number,
    policy_id,
    partner_id,
    policy_number,
    billing_cycle,
    total_amount_chf,
    due_date,
    invoice_status,
    amount_paid_chf,
    paid_at,
    created_at,
    updated_at
FROM iceberg.billing_silver.invoice""",
        ),
        (
            "Partner Events",
            """SELECT
    partner_id,
    insured_number,
    gender,
    partner_status,
    encrypted,
    deleted,
    updated_at
FROM iceberg.partner_silver.partner""",
        ),
        (
            "Product Events",
            """SELECT
    product_id,
    product_name,
    product_line,
    base_premium_chf,
    status,
    is_deprecated,
    updated_at
FROM iceberg.product_silver.product""",
        ),
        (
            "Claim Detail",
            "SELECT * FROM iceberg.claims_gold.claim_detail",
        ),
        (
            "Policy Detail",
            "SELECT * FROM iceberg.policy_gold.policy_detail",
        ),
        (
            "Financial Summary",
            "SELECT * FROM iceberg.billing_gold.financial_summary",
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

    # ── 2b. Sync dataset columns from Trino BEFORE chart creation ────────────
    # Column metadata must be present before charts reference the dataset —
    # otherwise dashboard-embedded charts with SUM/aggregate metrics can hang
    # waiting on columns that were not yet introspected at chart-commit time.
    # Additionally, every chart uses time_range="No filter" and include_time=False,
    # so we explicitly disable temporal semantics on all datasets to prevent
    # Superset from auto-binding the first TIMESTAMP column as main_dttm_col
    # and applying implicit dashboard-level time filters.
    for ds in datasets.values():
        try:
            ds.fetch_metadata()
            ds.main_dttm_col = None
            for col in ds.columns:
                col.is_dttm = False
            print(f"  Synced columns for '{ds.table_name}' ({len(ds.columns)} cols, dttm disabled)")
        except Exception as e:
            print(f"  ⚠ Column sync failed for '{ds.table_name}': {e}")
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

    ds_policy   = datasets["Policy Events"]
    ds_claims   = datasets["Claims Events"]
    ds_billing  = datasets["Billing Events"]
    ds_partner  = datasets["Partner Events"]
    ds_product  = datasets["Product Events"]
    ds_claim_detail    = datasets["Claim Detail"]
    ds_policy_detail   = datasets["Policy Detail"]
    ds_financial       = datasets["Financial Summary"]

    chart_policy_pie = get_or_create_chart(
        "Policen nach Status", "pie", ds_policy,
        {"metric": count_metric(), "groupby": ["policy_status"], "row_limit": 50},
    )
    chart_claims_pie = get_or_create_chart(
        "Schäden nach Status", "pie", ds_claims,
        {"metric": count_metric(), "groupby": ["status"], "row_limit": 50},
    )
    chart_billing_bar = get_or_create_chart(
        "Abrechnung – Rechnungen nach Status", "table", ds_billing,
        {
            "metrics": [count_metric()],
            "groupby": ["invoice_status"],
            "row_limit": 50,
            "include_time": False,
        },
    )
    chart_settlement = get_or_create_chart(
        "Schadenmeldungen Total", "big_number_total", ds_claims,
        {
            "metric": count_metric(),
            "subheader": "Claims in Iceberg (Silver)",
        },
    )
    chart_billing_total = get_or_create_chart(
        "Rechnungen Total", "big_number_total", ds_billing,
        {"metric": count_metric(), "subheader": "Invoices in Iceberg (Silver)"},
    )
    chart_partner_table = get_or_create_chart(
        "Partner – Übersicht nach Status", "table", ds_partner,
        {
            "metrics": [count_metric()],
            "groupby": ["partner_status"],
            "row_limit": 100,
            "include_time": False,
        },
    )
    chart_product_table = get_or_create_chart(
        "Produkte – aktuelle Übersicht", "table", ds_product,
        {
            "metrics": [count_metric()],
            "groupby": ["product_line", "product_name"],
            "row_limit": 100,
            "include_time": False,
        },
    )
    chart_claim_detail = get_or_create_chart(
        "Schadendetails (Gold)", "table", ds_claim_detail,
        {
            "metrics": [count_metric()],
            "groupby": ["product_line", "status"],
            "row_limit": 200,
            "include_time": False,
        },
    )
    chart_policy_detail = get_or_create_chart(
        "Policendetails (Gold)", "table", ds_policy_detail,
        {
            "metrics": [count_metric(), sum_metric("premium_chf", "Prämie CHF")],
            "groupby": ["product_line", "policy_status"],
            "row_limit": 200,
            "include_time": False,
        },
    )
    chart_financial = get_or_create_chart(
        "Finanzzusammenfassung (Gold)", "table", ds_financial,
        {
            "metrics": [
                sum_metric("total_billed_chf", "Total Billed CHF"),
                sum_metric("total_collected_chf", "Total Collected CHF"),
                sum_metric("total_outstanding_chf", "Total Outstanding CHF"),
            ],
            "groupby": ["collection_status"],
            "row_limit": 100,
            "include_time": False,
            "query_mode": "aggregate",
            "adhoc_filters": [],
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
        chart_claim_detail,
        chart_policy_detail,
        chart_financial,
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
    #         Row5: [claim_detail(6), policy_detail(6)]
    #         Row6: [financial(12)]
    c1_id, c1 = chart_pos(chart_policy_pie,   "ROW_1", 6)
    c2_id, c2 = chart_pos(chart_claims_pie,   "ROW_1", 6)
    c3_id, c3 = chart_pos(chart_billing_bar,  "ROW_2", 12)
    c4_id, c4 = chart_pos(chart_settlement,   "ROW_3", 4, height=34)
    c5_id, c5 = chart_pos(chart_billing_total,"ROW_3", 4, height=34)
    c6_id, c6 = chart_pos(chart_partner_table,"ROW_3", 4, height=34)
    c7_id, c7 = chart_pos(chart_product_table,"ROW_4", 12)
    c8_id, c8 = chart_pos(chart_claim_detail, "ROW_5", 6)
    c9_id, c9 = chart_pos(chart_policy_detail,"ROW_5", 6)
    c10_id, c10 = chart_pos(chart_financial,  "ROW_6", 12)

    positions = {
        "DASHBOARD_VERSION_KEY": "v2",
        "ROOT_ID":  {"type": "ROOT", "id": "ROOT_ID", "children": ["GRID_ID"]},
        "GRID_ID":  {"type": "GRID", "id": "GRID_ID", "children": ["ROW_1", "ROW_2", "ROW_3", "ROW_4", "ROW_5", "ROW_6"]},
        "ROW_1": {"type": "ROW", "id": "ROW_1", "children": [c1_id, c2_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_2": {"type": "ROW", "id": "ROW_2", "children": [c3_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_3": {"type": "ROW", "id": "ROW_3", "children": [c4_id, c5_id, c6_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_4": {"type": "ROW", "id": "ROW_4", "children": [c7_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_5": {"type": "ROW", "id": "ROW_5", "children": [c8_id, c9_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        "ROW_6": {"type": "ROW", "id": "ROW_6", "children": [c10_id],
                  "meta": {"background": "BACKGROUND_TRANSPARENT"}},
        c1_id: c1, c2_id: c2, c3_id: c3,
        c4_id: c4, c5_id: c5, c6_id: c6, c7_id: c7,
        c8_id: c8, c9_id: c9, c10_id: c10,
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
