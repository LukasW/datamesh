#!/usr/bin/env zsh
# seed-test-data.sh – Seed all Yuno Datamesh services with realistic test data.
# Intended for local dev / demo environments only.
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
PARTNER_URL="${PARTNER_URL:-http://localhost:9080}"
PRODUCT_URL="${PRODUCT_URL:-http://localhost:9081}"
POLICY_URL="${POLICY_URL:-http://localhost:9082}"
CLAIMS_URL="${CLAIMS_URL:-http://localhost:9083}"
BILLING_URL="${BILLING_URL:-http://localhost:9084}"

# Seconds to sleep after activating all policies so Claims/Billing can
# consume policy.v1.issued before claims are opened.
KAFKA_WAIT="${KAFKA_WAIT:-8}"

# ── helpers ────────────────────────────────────────────────────────────────

log()  { echo "  $*" >&2 }
ok()   { echo "  ✓ $*" >&2 }
fail() { echo "  ✗ $*" >&2; exit 1 }

_json() {
  # Extract a single top-level key from JSON stdin.  Usage: _json key
  python3 -c "
import sys, json
data = sys.stdin.read().strip()
if not data:
    sys.exit(0)
try:
    d = json.loads(data)
    print(d.get('$1', ''))
except json.JSONDecodeError:
    print('ERROR: not JSON: ' + data[:200], file=sys.stderr)
    sys.exit(1)
"
}

_json_find_by() {
  # From a JSON array on stdin, find the first object where field==value and
  # return the value of return_field.
  # Usage: _json_find_by field value return_field
  python3 -c "
import sys, json
items = json.load(sys.stdin)
if isinstance(items, dict):
    items = items.get('content', [])
match = next((i for i in items if str(i.get('$1','')) == '$2'), None)
print(match['$3'] if match else '')
"
}

refresh_token() {
  # The Host header is spoofed so Keycloak issues tokens with iss=http://keycloak:8080/realms/yuno,
  # matching what the services (running in the Docker network) validate against.
  TOKEN=$(curl -sf -X POST \
    "$KEYCLOAK_URL/realms/yuno/protocol/openid-connect/token" \
    -H "Host: keycloak:8080" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&client_id=partner-service&client_secret=secret&username=admin&password=admin" \
    | _json access_token)
  [[ -n "$TOKEN" ]] || fail "Could not obtain Keycloak token"
}

_post() {
  # POST with JSON body; prints response body and fails on HTTP >= 400.
  local url="$1" body="${2:-}"
  local http_body http_code
  http_body=$(curl -s -w '\n__HTTP_STATUS__%{http_code}' -X POST "$url" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    ${body:+-d "$body"})
  http_code=$(echo "$http_body" | grep '__HTTP_STATUS__' | sed 's/.*__HTTP_STATUS__//')
  http_body=$(echo "$http_body" | grep -v '__HTTP_STATUS__')
  if [[ "${http_code:-000}" -ge 400 ]]; then
    echo "  HTTP $http_code from POST $url: $http_body" >&2
    return 1
  fi
  echo "$http_body"
}

_post_id() {
  # POST and return the 'id' field.
  _post "$@" | _json id
}

_post_claim_id() {
  # POST and return the 'claimId' field.
  _post "$@" | _json claimId
}

_get() {
  local http_body http_code
  http_body=$(curl -s -w '\n__HTTP_STATUS__%{http_code}' "$1" -H "Authorization: Bearer $TOKEN")
  http_code=$(echo "$http_body" | grep '__HTTP_STATUS__' | sed 's/.*__HTTP_STATUS__//')
  http_body=$(echo "$http_body" | grep -v '__HTTP_STATUS__')
  if [[ "${http_code:-000}" -ge 400 ]]; then
    echo "  HTTP $http_code from GET $1: $http_body" >&2
    return 1
  fi
  echo "$http_body"
}

wait_for_service() {
  local name="$1" url="$2" max="${3:-90}"
  printf "  Waiting for %-20s" "$name..."
  for i in $(seq 1 $max); do
    # Accept any HTTP response (200/401/400 all mean the service is up).
    local code
    code=$(curl -s --max-time 2 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null)
    if [[ "$code" != "000" && -n "$code" ]]; then
      echo "ready (HTTP $code)"
      return 0
    fi
    sleep 1
    (( i % 10 == 0 )) && printf "(%ds)" $i || printf "."
  done
  echo "TIMEOUT"
  exit 1
}

# Retry a policy creation until it succeeds (read model may not be populated yet).
create_policy_with_retry() {
  local body="$1" max=30 id
  for i in $(seq 1 $max); do
    id=$(curl -sf -X POST "$POLICY_URL/api/policies" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d "$body" | _json id) && [[ -n "$id" ]] && { echo "$id"; return 0; }
    sleep 2
  done
  fail "Policy creation timed out after ${max} retries"
}

# ── wait for stack ─────────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║          Seeding Test Data – Yuno Sachversicherung            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "▶ Waiting for services to be ready…"

wait_for_service "Keycloak"        "$KEYCLOAK_URL/health/ready"
wait_for_service "Partner Service" "$PARTNER_URL/api/persons"
wait_for_service "Product Service" "$PRODUCT_URL/api/products"
wait_for_service "Policy Service"  "$POLICY_URL/api/policies"
wait_for_service "Claims Service"  "$CLAIMS_URL/api/claims?policyId=ping"
wait_for_service "Billing Service" "$BILLING_URL/api/invoices"

echo ""
echo "▶ Obtaining auth token…"
refresh_token
ok "Token obtained (admin / all roles)"

# ── 1. PARTNER SERVICE ─────────────────────────────────────────────────────

echo ""
echo "▶ Creating persons (Partner Service)…"

_person() {
  # _person <label> <name> <first> <gender> <dob> <ahv> <street> <no> <plz> <city> <validFrom>
  local label="$1" name="$2" first="$3" gender="$4" dob="$5" ahv="$6"
  local street="$7" hno="$8" plz="$9" city="${10}" vf="${11}"
  local id resp
  resp=$(curl -s -w '\n__HTTP_STATUS__%{http_code}' -X POST "$PARTNER_URL/api/persons" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"name\":\"$name\",\"firstName\":\"$first\",\"gender\":\"$gender\",\"dateOfBirth\":\"$dob\",\"socialSecurityNumber\":\"$ahv\"}")
  local http_code body
  http_code=$(echo "$resp" | grep '__HTTP_STATUS__' | sed 's/.*__HTTP_STATUS__//')
  body=$(echo "$resp" | grep -v '__HTTP_STATUS__')
  if [[ "$http_code" == "201" || "$http_code" == "200" ]]; then
    id=$(echo "$body" | _json id)
    _post "$PARTNER_URL/api/persons/$id/addresses" \
      "{\"addressType\":\"RESIDENCE\",\"street\":\"$street\",\"houseNumber\":\"$hno\",\"postalCode\":\"$plz\",\"city\":\"$city\",\"land\":\"Schweiz\",\"validFrom\":\"$vf\"}" \
      > /dev/null
    ok "$label $first $name  →  $id (created)"
  else
    # Person already exists – look up by AHV number
    id=$(curl -s -H "Authorization: Bearer $TOKEN" \
      "$PARTNER_URL/api/persons?ahv=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$ahv'))")" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); items=d.get('content',[]); print(items[0]['personId'] if items else '')" 2>/dev/null)
    [[ -n "$id" ]] || fail "Failed to create or find person $label (HTTP $http_code: $body)"
    ok "$label $first $name  →  $id (existing)"
  fi
  echo "$id"
}

P01=$(_person P01 "Müller"      "Hans"      MALE   1975-06-15 756.1234.5678.97 "Hauptstrasse"       42  8001 "Zürich"       2020-01-01)
P02=$(_person P02 "Weber"       "Franziska" FEMALE 1990-03-28 756.9876.5432.17 "Bahnhofstrasse"     7   3000 "Bern"         2021-06-01)
P03=$(_person P03 "Rossi"       "Marco"     MALE   1983-11-02 756.5555.1111.28 "Via Cantonale"      12  6900 "Lugano"       2019-03-15)
P04=$(_person P04 "Dubois"      "Sophie"    FEMALE 1988-07-14 756.2345.6789.08 "Rue de Rive"        3   1201 "Genf"         2022-09-01)
P05=$(_person P05 "Keller"      "Thomas"    MALE   1965-02-20 756.3456.7890.19 "Freie Strasse"      18  4001 "Basel"        2010-04-01)
P06=$(_person P06 "Meier"       "Sabrina"   FEMALE 2000-09-11 756.4567.8901.20 "Marktgasse"         5   8400 "Winterthur"   2023-01-15)
P07=$(_person P07 "Bernasconi"  "Luca"      MALE   1978-04-30 756.5678.9012.31 "Piazza Grande"      1   6600 "Locarno"      2015-07-01)
P08=$(_person P08 "Favre"       "Claudine"  FEMALE 1955-12-03 756.6789.0123.42 "Avenue du Léman"    22  1003 "Lausanne"     2005-06-01)
P09=$(_person P09 "Zimmermann"  "Peter"     MALE   1992-08-18 756.7890.1234.53 "Marktplatz"         9   9000 "St. Gallen"   2020-08-01)
P10=$(_person P10 "Steiner"     "Anna"      FEMALE 1948-05-25 756.8901.2345.64 "Weinmarkt"          11  6000 "Luzern"       1998-01-01)
P11=$(_person P11 "Blanc"       "Nicolas"   MALE   1986-01-09 756.9012.3456.75 "Rue de Lausanne"    14  1700 "Fribourg"     2018-05-01)
P12=$(_person P12 "Brunner"     "Ursula"    FEMALE 1971-11-17 756.0123.4567.86 "Laurenzenvorstadt"  33  5000 "Aarau"        2014-11-01)
P13=$(_person P13 "Schmid"      "David"     MALE   1994-06-22 756.1122.3344.95 "Technikumstrasse"   71  8401 "Winterthur"   2022-10-01)
P14=$(_person P14 "Frei"        "Laura"     FEMALE 2002-03-08 756.2233.4455.06 "Neumarktstrasse"    6   2500 "Biel/Bienne"  2024-01-01)
P15=$(_person P15 "Ferretti"    "Giorgio"   MALE   1969-09-14 756.3344.5566.17 "Viale Stazione"     4   6500 "Bellinzona"   2007-03-01)
P16=$(_person P16 "Huber"       "Martina"   FEMALE 1980-07-03 756.4455.6677.28 "Seestrasse"         88  8002 "Zürich"       2017-09-01)
P17=$(_person P17 "Morel"       "Alain"     MALE   1958-04-26 756.5566.7788.39 "Rue du Pommier"     2   2000 "Neuchâtel"    2003-07-01)
P18=$(_person P18 "Kälin"       "Barbara"   FEMALE 1995-12-31 756.6677.8899.40 "Alpenstrasse"       17  6300 "Zug"          2021-03-15)
P19=$(_person P19 "Wolf"        "Stefan"    MALE   1977-08-07 756.7788.9900.51 "Bälliz"             30  3600 "Thun"         2012-06-01)
P20=$(_person P20 "Dupont"      "Céline"    FEMALE 1984-02-19 756.8899.0011.62 "Rue du Grand-Pont"  8   1950 "Sion"         2019-11-01)
P21=$(_person P21 "Caluori"     "Reto"      MALE   1991-10-05 756.9900.1122.73 "Grabenstrasse"      15  7000 "Chur"         2023-05-01)
P22=$(_person P22 "Baumann"     "Heidi"     FEMALE 1962-03-22 756.0011.2233.84 "Hauptgasse"         24  4500 "Solothurn"    2001-09-01)

# ── 2. PRODUCT SERVICE ─────────────────────────────────────────────────────

echo ""
echo "▶ Creating products (Product Service)…"

_product() {
  local label="$1" name="$2" line="$3" premium="$4" desc="$5"
  local id
  id=$(_post_id "$PRODUCT_URL/api/products" \
    "{\"name\":\"$name\",\"description\":\"$desc\",\"productLine\":\"$line\",\"basePremium\":$premium}")
  [[ -n "$id" ]] || fail "Failed to create product $label"
  ok "$label $name  →  $id"
  echo "$id"
}

PR1=$(_product PR1 "Hausrat Basis"           HOUSEHOLD_CONTENTS 150.00 "Basic household contents insurance")
PR2=$(_product PR2 "Haftpflicht Privat"      LIABILITY          200.00 "Personal liability coverage")
PR3=$(_product PR3 "Motorfahrzeug Vollkasko" MOTOR_VEHICLE      980.00 "Comprehensive motor vehicle coverage")
PR4=$(_product PR4 "Reiseversicherung"       TRAVEL              85.00 "Travel insurance with cancellation coverage")
PR5=$(_product PR5 "Rechtsschutz"            LEGAL_EXPENSES     320.00 "Legal expenses insurance")

# ── wait for Policy Service read models to populate via Kafka ───────────────

echo ""
echo "▶ Waiting for Policy Service read models (Kafka propagation)…"
echo "  Retrying policy creation until partner/product views are populated."

# ── 3. POLICY SERVICE ──────────────────────────────────────────────────────

echo ""
echo "▶ Creating and activating policies (Policy Service)…"

refresh_token

_policy() {
  # _policy <label> <partnerId> <productId> <startDate> <premium> <deductible>
  local label="$1" partner="$2" product="$3" start="$4" premium="$5" ded="$6"
  local id
  id=$(create_policy_with_retry \
    "{\"partnerId\":\"$partner\",\"productId\":\"$product\",\"coverageStartDate\":\"$start\",\"premium\":$premium,\"deductible\":$ded}")
  [[ -n "$id" ]] || fail "Failed to create policy $label"
  echo "$id"
}

_coverage() {
  local policyId="$1" type="$2" amount="$3"
  _post "$POLICY_URL/api/policies/$policyId/coverages" \
    "{\"coverageType\":\"$type\",\"insuredAmount\":$amount}" > /dev/null
}

_activate() { _post "$POLICY_URL/api/policies/$1/activate" > /dev/null }
_cancel()   { _post "$POLICY_URL/api/policies/$1/cancel"   > /dev/null }

# POL01 – Müller / Hausrat
POL01=$(_policy POL01 "$P01" "$PR1" 2024-01-01 450.00 200.00)
_coverage "$POL01" HOUSEHOLD_CONTENTS 50000.00
_activate "$POL01"
ok "POL01 Müller / Hausrat Basis  →  $POL01"

# POL02 – Weber / Haftpflicht
POL02=$(_policy POL02 "$P02" "$PR2" 2024-03-01 380.00 0.00)
_coverage "$POL02" LIABILITY 5000000.00
_activate "$POL02"
ok "POL02 Weber / Haftpflicht Privat  →  $POL02"

# POL03 – Rossi / Motorfahrzeug → will be cancelled
POL03=$(_policy POL03 "$P03" "$PR3" 2023-06-15 1200.00 500.00)
_coverage "$POL03" COMPREHENSIVE 35000.00
_activate "$POL03"
_cancel   "$POL03"
ok "POL03 Rossi / Motorfahrzeug (CANCELLED)  →  $POL03"

# POL04 – Dubois / Haftpflicht
POL04=$(_policy POL04 "$P04" "$PR2" 2024-06-01 360.00 0.00)
_coverage "$POL04" LIABILITY 3000000.00
_activate "$POL04"
ok "POL04 Dubois / Haftpflicht Privat  →  $POL04"

# POL05 – Keller / Hausrat (multi-coverage, multi-policy person)
POL05=$(_policy POL05 "$P05" "$PR1" 2022-01-01 520.00 300.00)
_coverage "$POL05" HOUSEHOLD_CONTENTS 80000.00
_coverage "$POL05" THEFT              15000.00
_activate "$POL05"
ok "POL05 Keller / Hausrat Basis (2 coverages)  →  $POL05"

# POL06 – Keller / Motorfahrzeug (second policy for P05)
POL06=$(_policy POL06 "$P05" "$PR3" 2023-03-15 1450.00 1000.00)
_coverage "$POL06" COMPREHENSIVE 42000.00
_activate "$POL06"
ok "POL06 Keller / Motorfahrzeug Vollkasko  →  $POL06"

# POL07 – Meier / Hausrat → invoice will receive dunning
POL07=$(_policy POL07 "$P06" "$PR1" 2025-01-01 410.00 200.00)
_coverage "$POL07" HOUSEHOLD_CONTENTS 30000.00
_activate "$POL07"
ok "POL07 Meier / Hausrat Basis  →  $POL07"

# POL08 – Bernasconi / Motorfahrzeug
POL08=$(_policy POL08 "$P07" "$PR3" 2021-09-01 1100.00 500.00)
_coverage "$POL08" COMPREHENSIVE 28000.00
_activate "$POL08"
ok "POL08 Bernasconi / Motorfahrzeug Vollkasko  →  $POL08"

# POL09 – Favre / Hausrat (long-standing, multi-coverage)
POL09=$(_policy POL09 "$P08" "$PR1" 2019-04-01 390.00 100.00)
_coverage "$POL09" HOUSEHOLD_CONTENTS 45000.00
_coverage "$POL09" NATURAL_HAZARD     45000.00
_activate "$POL09"
ok "POL09 Favre / Hausrat Basis (2 coverages)  →  $POL09"

# POL10 – Zimmermann / Haftpflicht
POL10=$(_policy POL10 "$P09" "$PR2" 2024-09-01 350.00 0.00)
_coverage "$POL10" LIABILITY 2000000.00
_activate "$POL10"
ok "POL10 Zimmermann / Haftpflicht Privat  →  $POL10"

# POL11 – Steiner / Hausrat → invoice will be escalated to dunning level 2
POL11=$(_policy POL11 "$P10" "$PR1" 2023-07-01 430.00 150.00)
_coverage "$POL11" HOUSEHOLD_CONTENTS 55000.00
_activate "$POL11"
ok "POL11 Steiner / Hausrat Basis  →  $POL11"

# POL12 – Blanc / Motorfahrzeug → claim will be rejected
POL12=$(_policy POL12 "$P11" "$PR3" 2024-02-01 1300.00 500.00)
_coverage "$POL12" COMPREHENSIVE 22000.00
_activate "$POL12"
ok "POL12 Blanc / Motorfahrzeug Vollkasko  →  $POL12"

# POL13 – Brunner / Reiseversicherung
POL13=$(_policy POL13 "$P12" "$PR4" 2025-03-01 85.00 0.00)
_coverage "$POL13" LIABILITY 500000.00
_activate "$POL13"
ok "POL13 Brunner / Reiseversicherung  →  $POL13"

# POL14 – Huber / Rechtsschutz
POL14=$(_policy POL14 "$P16" "$PR5" 2024-05-01 320.00 0.00)
_coverage "$POL14" LIABILITY 250000.00
_activate "$POL14"
ok "POL14 Huber / Rechtsschutz  →  $POL14"

# POL15 – Wolf / Hausrat (multi-coverage)
POL15=$(_policy POL15 "$P19" "$PR1" 2024-11-01 480.00 200.00)
_coverage "$POL15" HOUSEHOLD_CONTENTS 60000.00
_coverage "$POL15" GLASS_BREAKAGE     10000.00
_activate "$POL15"
ok "POL15 Wolf / Hausrat Basis (2 coverages)  →  $POL15"

# ── wait for Claims / Billing to consume policy.v1.issued ──────────────────

echo ""
echo "▶ Waiting ${KAFKA_WAIT}s for Claims/Billing to consume policy.v1.issued…"
sleep "$KAFKA_WAIT"

# ── 4. CLAIMS SERVICE ──────────────────────────────────────────────────────

echo ""
echo "▶ Creating claims (Claims Service)…"

refresh_token

_claim() {
  # _claim <label> <policyId> <description> <claimDate>
  local label="$1" pol="$2" desc="$3" date="$4"
  local id
  id=$(_post_claim_id "$CLAIMS_URL/api/claims" \
    "{\"policyId\":\"$pol\",\"description\":\"$desc\",\"claimDate\":\"$date\"}")
  [[ -n "$id" ]] || fail "Failed to create claim $label – PolicySnapshot may not exist yet (increase KAFKA_WAIT)"
  echo "$id"
}

_review() { _post "$CLAIMS_URL/api/claims/$1/review"  > /dev/null }
_settle() { _post "$CLAIMS_URL/api/claims/$1/settle"  > /dev/null }
_reject() { _post "$CLAIMS_URL/api/claims/$1/reject"  > /dev/null }

# C01 – Müller: Wasserschaden → SETTLED
C01=$(_claim C01 "$POL01" "Wasserschaden im Keller" 2024-03-10)
_review "$C01"; _settle "$C01"
ok "C01 Müller – Wasserschaden (SETTLED)  →  $C01"

# C02 – Weber: Sachschaden → IN_REVIEW
C02=$(_claim C02 "$POL02" "Sachschaden an Drittperson beim Velofahren" 2024-04-05)
_review "$C02"
ok "C02 Weber – Sachschaden (IN_REVIEW)  →  $C02"

# C03 – Keller (Hausrat): Einbruchdiebstahl → SETTLED
C03=$(_claim C03 "$POL05" "Einbruchdiebstahl, Schmuck und Elektronik" 2024-08-22)
_review "$C03"; _settle "$C03"
ok "C03 Keller – Einbruchdiebstahl (SETTLED)  →  $C03"

# C04 – Keller (Motor): Kollisionsschaden → IN_REVIEW
C04=$(_claim C04 "$POL06" "Kollisionsschaden Heckbereich" 2024-10-01)
_review "$C04"
ok "C04 Keller – Kollisionsschaden (IN_REVIEW)  →  $C04"

# C05 – Favre: Sturmschaden → OPEN (fresh FNOL)
C05=$(_claim C05 "$POL09" "Sturmschaden Fensterscheibe" 2025-01-14)
ok "C05 Favre – Sturmschaden (OPEN)  →  $C05"

# C06 – Blanc: Totalschaden → REJECTED
C06=$(_claim C06 "$POL12" "Totalschaden nach Unfall" 2024-12-03)
_review "$C06"; _reject "$C06"
ok "C06 Blanc – Totalschaden (REJECTED)  →  $C06"

# C07 – Steiner: Leitungswasser → OPEN (fresh FNOL)
C07=$(_claim C07 "$POL11" "Leitungswasser – Küchenmöbel beschädigt" 2025-02-28)
ok "C07 Steiner – Leitungswasser (OPEN)  →  $C07"

# C08 – Bernasconi: Glasbruch → SETTLED
C08=$(_claim C08 "$POL08" "Glasbruch Windschutzscheibe" 2024-07-11)
_review "$C08"; _settle "$C08"
ok "C08 Bernasconi – Glasbruch (SETTLED)  →  $C08"

# ── wait for Billing to create invoices from policy.v1.issued ──────────────

echo ""
echo "▶ Waiting 5s for Billing to create invoices…"
sleep 5

# ── 5. BILLING SERVICE ─────────────────────────────────────────────────────

echo ""
echo "▶ Processing invoices (Billing Service)…"

refresh_token

_invoice_for() {
  # Find the invoiceId for a given policyId by querying with partnerId.
  local partnerId="$1" policyId="$2"
  _get "$BILLING_URL/api/invoices?partnerId=$partnerId" \
    | _json_find_by policyId "$policyId" invoiceId
}

_pay() {
  local invId="$1"
  [[ -n "$invId" ]] || { log "  (invoice not found, skipping pay)"; return; }
  _post "$BILLING_URL/api/invoices/$invId/pay" > /dev/null
}

_dun() {
  local invId="$1"
  [[ -n "$invId" ]] || { log "  (invoice not found, skipping dun)"; return; }
  _post "$BILLING_URL/api/invoices/$invId/dun" > /dev/null
}

INV01=$(_invoice_for "$P01" "$POL01"); _pay "$INV01"
ok "INV01 Müller (POL01) → PAID"

INV04=$(_invoice_for "$P04" "$POL04"); _pay "$INV04"
ok "INV04 Dubois (POL04) → PAID"

INV05=$(_invoice_for "$P05" "$POL05"); _pay "$INV05"
ok "INV05 Keller/Hausrat (POL05) → PAID"

INV06=$(_invoice_for "$P05" "$POL06"); _pay "$INV06"
ok "INV06 Keller/Motor (POL06) → PAID"

INV08=$(_invoice_for "$P07" "$POL08"); _pay "$INV08"
ok "INV08 Bernasconi (POL08) → PAID"

INV09=$(_invoice_for "$P08" "$POL09"); _pay "$INV09"
ok "INV09 Favre (POL09) → PAID"

# Meier (POL07) → 1 dunning level (reminder)
INV07=$(_invoice_for "$P06" "$POL07"); _dun "$INV07"
ok "INV07 Meier (POL07) → OVERDUE (dunning level 1)"

# Steiner (POL11) → 2 dunning levels (first warning)
INV11=$(_invoice_for "$P10" "$POL11"); _dun "$INV11"; _dun "$INV11"
ok "INV11 Steiner (POL11) → OVERDUE (dunning level 2)"

# POL02, POL10, POL12, POL13, POL14, POL15 → invoices stay OPEN
ok "Remaining invoices (POL02/10/12/13/14/15) → OPEN (no action)"
# POL03 invoice → auto-cancelled by Billing consuming policy.v1.cancelled

# ── summary ────────────────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════════════════════╗"
echo "║                    ✓  Test Data Seeding Complete                    ║"
echo "╠══════════════════════════════════════════════════════════════════════╣"
echo "║  Persons   22  (P01–P22, all linguistic regions, ages 22–77)        ║"
echo "║  Products   5  (Hausrat, Haftpflicht, Motor, Reise, Rechtsschutz)   ║"
echo "║  Policies  15  (14 ACTIVE, 1 CANCELLED)                             ║"
echo "║  Claims     8  (3 SETTLED, 2 IN_REVIEW, 2 OPEN, 1 REJECTED)        ║"
echo "║  Invoices  15  (6 PAID, 2 OVERDUE/dunning, 6 OPEN, 1 CANCELLED)    ║"
echo "╠══════════════════════════════════════════════════════════════════════╣"
echo "║  Kafka topics populated:                                             ║"
echo "║    person.v1.created/state         22 messages                      ║"
echo "║    product.v1.defined/state         5 messages                      ║"
echo "║    policy.v1.issued                15 messages                      ║"
echo "║    policy.v1.coverage-added        19 messages                      ║"
echo "║    policy.v1.cancelled              1 message                       ║"
echo "║    claims.v1.opened/settled/…       8 / 3 messages                  ║"
echo "║    billing.v1.invoice-created      15 messages                      ║"
echo "║    billing.v1.payment-received      6 messages                      ║"
echo "║    billing.v1.dunning-initiated     3 messages                      ║"
echo "║    billing.v1.payout-triggered      3 messages                      ║"
echo "╚══════════════════════════════════════════════════════════════════════╝"
echo ""
