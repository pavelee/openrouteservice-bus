#!/usr/bin/env bash
#
# refresh-ors.sh — pełny refresh map OSM + grafów ORS, jednym ruchem.
#
# Co robi (po kolei):
#   1. Pobiera świeży PBF z Geofabrik do staging
#   2. PBF → XML (convert_osm_to_xml.py)
#   3. Poprawki prywatnych dróg (fix_private_roads.py)
#   4. Uruchamia ors-builder z profilu compose i czeka aż zbuduje grafy do graphs_staging/
#   5. Zatrzymuje buildera, robi atomic swap (graphs → graphs_old, graphs_staging → graphs)
#   6. `docker rollout ors-app` — stawia nowy kontener obok starego (już z podmienionym
#      grafem, bo dzieli ten sam bind mount), czeka aż przejdzie healthcheck, dopiero
#      wtedy gasi stary. Stary kontener cały czas serwuje ruch na starym grafie z pamięci.
#   7. Na sukces — sprząta. Na błąd po swapie — ROLLBACK do graphs_old.
#
# Zero-downtime ors-app (patrz docker-compose.yml + README.md "Zero-downtime deploy").
# Build (~15–30 min) idzie obok produkcji, sam rollout to tyle ile trwa załadowanie
# nowego grafu (do ORS_APP_TIMEOUT).
#
# Wymagania: docker, wget, venv pod script/env/ z osmium + lxml, plugin `docker-rollout`
# zainstalowany jako `docker rollout` (patrz README.md).

set -euo pipefail

# ============================== Konfiguracja ==================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ORS_ROOT}/docker-compose.yml"

# Repo root compose (definiuje faktyczny produkcyjny ors-app + traefik).
# COMPOSE_FILE (submodule) służy tylko do ors-builder (profil "builder").
ROOT_COMPOSE_FILE="$(cd "${ORS_ROOT}/.." && pwd)/docker-compose.yml"

ORS_DOCKER="${ORS_ROOT}/ors-docker"
FILES_DIR="${ORS_DOCKER}/files"
STAGING_DIR="${FILES_DIR}/staging"
GRAPHS_DIR="${ORS_DOCKER}/graphs"
GRAPHS_STAGING="${ORS_DOCKER}/graphs_staging"
GRAPHS_OLD="${ORS_DOCKER}/graphs_old"

OSM_URL="https://download.geofabrik.de/europe/poland/mazowieckie-latest.osm.pbf"
PBF_FILE="${STAGING_DIR}/mazowieckie-latest.osm.pbf"
XML_RAW="${STAGING_DIR}/mazowieckie-latest.osm"
XML_PROCESSED="${STAGING_DIR}/mazowieckie.osm"

PROD_XML="${FILES_DIR}/mazowieckie.osm"
PROD_XML_BACKUP="${FILES_DIR}/mazowieckie.osm.prev"

VENV_PYTHON="${SCRIPT_DIR}/env/bin/python3"
LOCK_DIR="/tmp/traska-refresh-ors.lock"

# Endpoint do healthchecka ORS (z poziomu hosta, dla ors-app)
ORS_APP_HEALTH_URL="http://localhost:8080/ors/v2/health"

# Rejestr interwencji routingowych (aplikacja web) — źródło syntetycznych way'ów dla
# fix_private_roads.py i cel callbacku "baked" po udanym rollout. CRON_SECRET bierzemy
# z env, a gdy brak — z web/.env. Wszystko nieblokujące: brak sekretu/API = build
# z samym bootstrapem SYNTHETIC_WAYS (patrz load_synthetic_ways w fix_private_roads.py).
TRASKA_APP_URL="${TRASKA_APP_URL:-http://localhost:3000}"
WEB_ENV_FILE="$(cd "${ORS_ROOT}/.." && pwd)/web/.env"
if [ -z "${CRON_SECRET:-}" ] && [ -f "${WEB_ENV_FILE}" ]; then
    CRON_SECRET="$(grep -E '^CRON_SECRET=' "${WEB_ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"' || true)"
fi
SYNTHETIC_WAYS_MANIFEST="${STAGING_DIR}/synthetic-ways-manifest.json"

# Timeouty
BUILDER_TIMEOUT=3600    # 60 min na build grafów
BUILDER_POLL=30
ORS_APP_TIMEOUT=300     # 5 min na `docker rollout` (healthcheck nowej repliki ors-app)
ORS_APP_POLL=5

# Stan dla rollbacka — czy zdążyliśmy podmienić katalogi
SWAPPED=0

# ============================== Logowanie ====================================

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
err()  { printf '[%s] [ERROR] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
step() { printf '\n[%s] ===== %s =====\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

# ============================== Lock =========================================

if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
    err "Inna instancja refresh-ors.sh już biegnie (lock: ${LOCK_DIR})."
    err "Jeśli na pewno nic nie działa: rm -rf ${LOCK_DIR}"
    exit 1
fi

# ============================== Cleanup / Rollback ===========================

rollback() {
    err "ROLLBACK: przywracam poprzedni stan"

    if [ -d "${GRAPHS_OLD}" ]; then
        rm -rf "${GRAPHS_DIR}.failed" 2>/dev/null || true
        [ -d "${GRAPHS_DIR}" ] && mv "${GRAPHS_DIR}" "${GRAPHS_DIR}.failed"
        mv "${GRAPHS_OLD}" "${GRAPHS_DIR}"
        log "✓ Grafy przywrócone (failed wersja w graphs.failed)"
    fi

    if [ -f "${PROD_XML_BACKUP}" ]; then
        rm -f "${PROD_XML}.failed" 2>/dev/null || true
        [ -f "${PROD_XML}" ] && mv "${PROD_XML}" "${PROD_XML}.failed"
        mv "${PROD_XML_BACKUP}" "${PROD_XML}"
        log "✓ XML przywrócony (failed wersja w mazowieckie.osm.failed)"
    fi

    # `docker rollout` na niepowodzeniu healthchecka sam usuwa niezdrową nową
    # replikę i zostawia starą (wciąż serwującą ze starego grafu w pamięci)
    # działającą. Dla pewności wymuszamy dokładnie 1 replikę na (teraz
    # przywróconych) plikach, niezależnie w jakim stanie rollout się urwał —
    # scale-down usuwa replikę o wyższym indeksie, czyli tę nowszą/niezdrową.
    log "Wymuszam dokładnie 1 zdrową replikę ors-app po rollbacku..."
    docker compose -f "${ROOT_COMPOSE_FILE}" up -d --scale ors-app=1 ors-app >/dev/null 2>&1 \
        || err "Nie udało się przywrócić ors-app — wymagana ręczna interwencja"

    err "ROLLBACK ZAKOŃCZONY. Sprawdź ors-app i pliki *.failed."
}

cleanup() {
    local rc=$?

    # Builder zatrzymujemy zawsze — czy sukces, czy porażka
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^ors-builder$'; then
        log "Cleanup: zatrzymuję ors-builder"
        docker compose -f "${COMPOSE_FILE}" --profile builder stop ors-builder >/dev/null 2>&1 || true
        docker compose -f "${COMPOSE_FILE}" --profile builder rm -f ors-builder >/dev/null 2>&1 || true
    fi

    if [ ${rc} -ne 0 ] && [ ${SWAPPED} -eq 1 ]; then
        rollback
    fi

    rmdir "${LOCK_DIR}" 2>/dev/null || true
    exit ${rc}
}
trap cleanup EXIT

# ============================== Preflight ====================================

step "Preflight"

command -v docker >/dev/null || { err "docker nie znaleziony w PATH"; exit 1; }
command -v wget   >/dev/null || { err "wget nie znaleziony w PATH"; exit 1; }
[ -x "${VENV_PYTHON}" ]      || { err "Brak python3 w venv: ${VENV_PYTHON}"; exit 1; }
[ -f "${COMPOSE_FILE}" ]     || { err "Brak compose: ${COMPOSE_FILE}"; exit 1; }
[ -d "${FILES_DIR}" ]        || { err "Brak katalogu: ${FILES_DIR}"; exit 1; }

log "Czyszczę staging z poprzednich biegów..."
rm -rf "${STAGING_DIR}" "${GRAPHS_STAGING}"
mkdir -p "${STAGING_DIR}"

# ============================== 1. Download ==================================

step "1/6 Pobieranie PBF z Geofabrik"
log "URL: ${OSM_URL}"
wget --progress=dot:giga -O "${PBF_FILE}" "${OSM_URL}"
log "✓ Pobrano $(du -h "${PBF_FILE}" | cut -f1) → ${PBF_FILE}"

# ============================== 2. PBF → XML =================================

step "2/6 Konwersja PBF → XML"
"${VENV_PYTHON}" - <<PY
import sys
sys.path.insert(0, "${SCRIPT_DIR}")
from convert_osm_to_xml import convert_pbf_to_osm_xml
convert_pbf_to_osm_xml("${PBF_FILE}", "${XML_RAW}")
PY
[ -f "${XML_RAW}" ] || { err "Konwersja nie wyprodukowała ${XML_RAW}"; exit 1; }
log "✓ XML: $(du -h "${XML_RAW}" | cut -f1)"

# Surowy PBF nam już niepotrzebny — wolimy odzyskać miejsce
rm -f "${PBF_FILE}"

# ============================== 3. Fix private roads =========================

step "3/6 Poprawki prywatnych dróg i ręcznych korekt"
# fix_private_roads.py pyta input() jeśli plik istnieje → usuwamy proaktywnie
rm -f "${XML_PROCESSED}"
TRASKA_APP_URL="${TRASKA_APP_URL}" \
CRON_SECRET="${CRON_SECRET:-}" \
SYNTHETIC_WAYS_MANIFEST="${SYNTHETIC_WAYS_MANIFEST}" \
    "${VENV_PYTHON}" "${SCRIPT_DIR}/fix_private_roads.py" "${XML_RAW}" "${XML_PROCESSED}"
[ -f "${XML_PROCESSED}" ] || { err "Brak ${XML_PROCESSED} po fix_private_roads"; exit 1; }
log "✓ Przetworzono: $(du -h "${XML_PROCESSED}" | cut -f1)"

rm -f "${XML_RAW}"

# ============================== 4. Build grafów ==============================

step "4/6 Build grafów (ors-builder)"
mkdir -p "${GRAPHS_STAGING}"

# Builder dzieli obraz z ors-app (lokalny fork). Jeśli image nie istnieje,
# zbudujmy go zawczasu — inaczej `up -d` zrobi to "po cichu" i timeout
# pollingu zdrowia może źle zinterpretować długi czas budowania obrazu.
if ! docker image inspect local/openrouteservice:v9.4.0 >/dev/null 2>&1; then
    log "Obraz local/openrouteservice:v9.4.0 nie istnieje — buduję..."
    docker compose -f "${COMPOSE_FILE}" --profile builder build ors-builder
fi

log "Start ors-builder przez profil 'builder'..."
docker compose -f "${COMPOSE_FILE}" --profile builder up -d ors-builder

log "Czekam aż grafy się zbudują (timeout ${BUILDER_TIMEOUT}s)..."
start_ts=$(date +%s)
while true; do
    elapsed=$(( $(date +%s) - start_ts ))
    if [ ${elapsed} -gt ${BUILDER_TIMEOUT} ]; then
        err "Timeout buildera po ${elapsed}s"
        log "Ostatnie logi buildera:"
        docker logs --tail=200 ors-builder 2>&1 | tail -100 >&2 || true
        exit 1
    fi

    # Builder nie wystawia portu na hoście — pollujemy przez exec.
    # ORS health: 200 + {"status":"ready"} kiedy grafy załadowane.
    if docker exec ors-builder wget -qO- http://localhost:8082/ors/v2/health 2>/dev/null \
         | grep -q '"status":"ready"'; then
        log "✓ Build ukończony po ${elapsed}s"
        break
    fi

    # Wykryj awarię kontenera — jeśli umarł, nie ma sensu dalej czekać
    if ! docker ps --format '{{.Names}}' | grep -q '^ors-builder$'; then
        err "Kontener ors-builder zniknął — sprawdź logi"
        docker logs --tail=200 ors-builder 2>&1 | tail -100 >&2 || true
        exit 1
    fi

    log "  ... build w toku (${elapsed}s)"
    sleep ${BUILDER_POLL}
done

log "Zatrzymuję ors-builder..."
docker compose -f "${COMPOSE_FILE}" --profile builder stop ors-builder >/dev/null
docker compose -f "${COMPOSE_FILE}" --profile builder rm -f ors-builder >/dev/null

# Sanity check — czy graphs_staging faktycznie się zapełnił
if [ -z "$(ls -A "${GRAPHS_STAGING}" 2>/dev/null)" ]; then
    err "graphs_staging jest pusty po buildzie — coś się wysypało"
    exit 1
fi
log "✓ graphs_staging zapełniony"

# ============================== 5. Atomic swap ===============================

step "5/6 Atomic swap"

# Usuń pozostałości po poprzednim udanym biegu (gdyby cleanup się nie wykonał)
rm -rf "${GRAPHS_OLD}"

# Po tym punkcie zaczyna się stan "świat zmieniony" — rollback aktywny
SWAPPED=1

if [ -d "${GRAPHS_DIR}" ]; then
    mv "${GRAPHS_DIR}" "${GRAPHS_OLD}"
fi
mv "${GRAPHS_STAGING}" "${GRAPHS_DIR}"
log "✓ graphs → graphs_old, graphs_staging → graphs"

if [ -f "${PROD_XML}" ]; then
    mv "${PROD_XML}" "${PROD_XML_BACKUP}"
fi
mv "${XML_PROCESSED}" "${PROD_XML}"
log "✓ mazowieckie.osm podmieniony (backup w .prev)"

# ============================== 6. Rollout ors-app (zero-downtime) ===========

step "6/6 Rollout ors-app (zero-downtime) i weryfikacja"

# `docker rollout` stawia nowy kontener ors-app obok starego — dzieli ten sam
# bind mount, więc od razu widzi grafy podmienione w kroku 5. Czeka aż przejdzie
# healthcheck z docker-compose.yml (sprawdza "status":"ready", nie tylko HTTP 200),
# dopiero wtedy gasi stary kontener. Stary serwuje ruch bez przerwy przez cały czas.
log "docker rollout ors-app (timeout ${ORS_APP_TIMEOUT}s)..."
docker rollout --timeout "${ORS_APP_TIMEOUT}" -f "${ROOT_COMPOSE_FILE}" ors-app

log "Weryfikacja end-to-end przez ${ORS_APP_HEALTH_URL} (przez traefik)..."
start_ts=$(date +%s)
while true; do
    elapsed=$(( $(date +%s) - start_ts ))
    if [ ${elapsed} -gt 30 ]; then
        err "ors-app nie odpowiada 'ready' przez publiczny endpoint mimo udanego rollout"
        exit 1
    fi

    if wget -qO- "${ORS_APP_HEALTH_URL}" 2>/dev/null | grep -q '"status":"ready"'; then
        log "✓ ors-app READY (weryfikacja end-to-end, ${elapsed}s)"
        break
    fi

    sleep ${ORS_APP_POLL}
done

# ============================== Callback "baked" =============================

# Graf z syntetycznymi way'ami serwuje ruch — dopiero TERAZ wolno oznaczyć je jako
# BAKED w rejestrze interwencji (uzbraja bramki czasowe w routingu aplikacji).
# Nieblokujące: błąd = way'e zostają PENDING i zostaną oznaczone przy kolejnym refreshu.
if [ -f "${SYNTHETIC_WAYS_MANIFEST}" ] && [ -n "${CRON_SECRET:-}" ]; then
    step "Callback baked do rejestru interwencji"
    if wget -qO- --header="Authorization: Bearer ${CRON_SECRET}" \
            --header="Content-Type: application/json" \
            --post-file="${SYNTHETIC_WAYS_MANIFEST}" \
            "${TRASKA_APP_URL}/api/routing-interventions/graph-export/baked" >/dev/null 2>&1; then
        log "✓ Rejestr interwencji powiadomiony (baked)"
    else
        err "Nie udało się powiadomić rejestru interwencji (baked) — way'e zostają PENDING (nieblokujące)"
    fi
fi

# ============================== Cleanup po sukcesie ==========================

step "Cleanup"
rm -rf "${GRAPHS_OLD}"
rm -f  "${PROD_XML_BACKUP}"
rm -rf "${STAGING_DIR}"
log "✓ Usunięto staging i backupy"

# Świat jest spójny — wyłączamy rollback z trapa
SWAPPED=0

step "SUKCES — refresh ORS zakończony"
