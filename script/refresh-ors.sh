#!/usr/bin/env bash
#
# refresh-ors.sh — pełny refresh map OSM + grafów ORS, jednym ruchem.
#
# Co robi (po kolei):
#   1. Pobiera świeży PBF z Geofabrik do staging
#   2. Transformacja mapy (transform_osm.py, PBF→PBF): interwencje z rejestru
#      (blokady, korekty tagów, pominięcia relacji, synthetic ways, bus:on_route)
#   3. Uruchamia ors-builder z profilu compose i czeka aż zbuduje grafy do graphs_staging/
#   4. Zatrzymuje buildera, robi atomic swap (graphs → graphs_old, graphs_staging → graphs)
#   5. Recreate ors-app z ROOT compose na nowych grafach i czeka na ready.
#      UWAGA: okno niedostępności = czas ładowania grafu (~1-3 min). Zero-downtime
#      (traefik + docker rollout) żyje na gałęzi `zero_down_time` i wróci po jej
#      merge — obecny main ma container_name + sztywny port, rollout nie zadziała.
#   6. Na sukces — sprząta. Na błąd po swapie — ROLLBACK do graphs_old.
#
# Build (~15–30 min) idzie obok produkcji (stary ors-app serwuje przez cały build).
#
# Wymagania: docker, wget, venv pod script/env/ z osmium (pyosmium).

set -euo pipefail

# ============================== Konfiguracja ==================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ORS_ROOT}/docker-compose.yml"

# Repo root compose (definiuje faktyczny produkcyjny ors-app).
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
MAP_PROCESSED="${STAGING_DIR}/mazowieckie.osm.pbf"

# Produkcyjna mapa (source_file w prod-ors-config.yml). Dawniej XML (mazowieckie.osm);
# od przejścia na transform_osm.py — PBF.
PROD_MAP="${FILES_DIR}/mazowieckie.osm.pbf"
PROD_MAP_BACKUP="${FILES_DIR}/mazowieckie.osm.pbf.prev"

VENV_PYTHON="${SCRIPT_DIR}/env/bin/python3"
LOCK_DIR="/tmp/traska-refresh-ors.lock"

# Endpoint do healthchecka ORS (z poziomu hosta, dla ors-app)
ORS_APP_HEALTH_URL="http://localhost:8080/ors/v2/health"

# Rejestr interwencji routingowych (aplikacja web) — źródło danych transformacji
# mapy (transform_osm.py) i cel callbacku "baked" po udanym restarcie. CRON_SECRET
# bierzemy z env, a gdy brak — z web/.env. Wszystko nieblokujące: brak sekretu/API
# = snapshot ostatniego eksportu, a w ostateczności bootstrapy w skrypcie
# (patrz load_graph_interventions w transform_osm.py).
TRASKA_APP_URL="${TRASKA_APP_URL:-http://localhost:3000}"
WEB_ENV_FILE="$(cd "${ORS_ROOT}/.." && pwd)/web/.env"
if [ -z "${CRON_SECRET:-}" ] && [ -f "${WEB_ENV_FILE}" ]; then
    CRON_SECRET="$(grep -E '^CRON_SECRET=' "${WEB_ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"' || true)"
fi
# Klucz do centralnego app_log (POST /api/v1/logs) — jak w cron/import-script.sh.
if [ -z "${TRASKA_API_KEY:-}" ] && [ -f "${WEB_ENV_FILE}" ]; then
    TRASKA_API_KEY="$(grep -E '^TRASKA_API_KEY=' "${WEB_ENV_FILE}" | head -1 | cut -d= -f2- | tr -d '"' || true)"
fi
SYNTHETIC_WAYS_MANIFEST="${STAGING_DIR}/synthetic-ways-manifest.json"
# Snapshot ostatniego UDANEGO eksportu interwencji grafowych — fallback dla
# skryptu transformacji mapy, gdy rejestr (API) jest niedostępny. Poza staging,
# bo staging jest czyszczony na starcie każdego biegu.
GRAPH_INTERVENTIONS_SNAPSHOT="${FILES_DIR}/graph-interventions-snapshot.json"

# Timeouty
BUILDER_TIMEOUT=3600    # 60 min na build grafów
BUILDER_POLL=30
ORS_APP_TIMEOUT=300     # 5 min na start ors-app (załadowanie nowego grafu do ready)
ORS_APP_POLL=5

# Stan dla rollbacka — czy zdążyliśmy podmienić katalogi
SWAPPED=0
# Czy krok 5 zdążył zdjąć stary kontener ors-app (wtedy rollback musi go odtworzyć,
# nawet jeśli jakiś kontener o tej nazwie "działa" — może być w crash-loopie).
REPLACED=0

# ============================== Logowanie ====================================

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
err()  { printf '[%s] [ERROR] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
step() { printf '\n[%s] ===== %s =====\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

# report_log <LEVEL> <message> — centralny app_log aplikacji (panel zdrowia).
# NIGDY nie przerywa refreshu (wzorzec cron/import-script.sh). Komunikaty
# "Migration started/completed/failed" rozpoznaje panel zdrowia — nie zmieniać.
LOG_SOURCE="script.refresh-ors"
REFRESH_START_TS=$(date +%s)
report_log() {
    local level="$1" message="$2"
    [ -n "${TRASKA_API_KEY:-}" ] || return 0
    local duration_ms=$(( ($(date +%s) - REFRESH_START_TS) * 1000 ))
    local body_file
    body_file="$(mktemp)" || return 0
    printf '{"level":"%s","source":"%s","message":"%s","payload":{"durationMs":%s}}' \
        "$level" "$LOG_SOURCE" "$message" "$duration_ms" > "$body_file"
    wget -qO- --timeout=15 \
        --header="Content-Type: application/json" \
        --header="x-api-key: ${TRASKA_API_KEY}" \
        --post-file="$body_file" \
        "${TRASKA_APP_URL}/api/v1/logs" >/dev/null 2>&1 \
      || log "WARN: nie udało się zaraportować do app_log (${level}: ${message})"
    rm -f "$body_file"
}

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

    if [ -f "${PROD_MAP_BACKUP}" ]; then
        rm -f "${PROD_MAP}.failed" 2>/dev/null || true
        [ -f "${PROD_MAP}" ] && mv "${PROD_MAP}" "${PROD_MAP}.failed"
        mv "${PROD_MAP_BACKUP}" "${PROD_MAP}"
        log "✓ Mapa przywrócona (failed wersja w mazowieckie.osm.pbf.failed)"
    fi

    # Po przywróceniu plików upewnij się, że ors-app działa NA PRZYWRÓCONYCH
    # grafach. Jeśli krok 5 zdążył podmienić kontener (REPLACED=1), ZAWSZE
    # odtwarzamy — kontener po nieudanym starcie bywa "running" w crash-loopie
    # i sam test `docker ps` kłamie (awaria 2026-07-09: kontener na złym obrazie
    # restartował się w kółko, a rollback zostawiał go "bez zmian").
    if [ "${REPLACED}" -eq 1 ] || ! docker ps --format '{{.Names}}' | grep -q '^ors-app$'; then
        log "Odtwarzam ors-app na przywróconych grafach..."
        docker rm -f ors-app >/dev/null 2>&1 || true
        docker compose -f "${ROOT_COMPOSE_FILE}" up -d ors-app >/dev/null 2>&1 \
            || err "Nie udało się przywrócić ors-app — wymagana ręczna interwencja"
    else
        log "ors-app nadal działa (stary kontener, błąd przed restartem) — bez zmian"
    fi

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

    # Konwencja panelu zdrowia: Migration started/completed/failed.
    if [ ${rc} -ne 0 ]; then
        report_log ERROR "Migration failed"
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

report_log INFO "Migration started"

# ============================== 1. Download ==================================

step "1/5 Pobieranie PBF z Geofabrik"
log "URL: ${OSM_URL}"
wget --progress=dot:giga -O "${PBF_FILE}" "${OSM_URL}"
log "✓ Pobrano $(du -h "${PBF_FILE}" | cut -f1) → ${PBF_FILE}"

# ============================== 2. Transformacja mapy ========================

step "2/5 Transformacja mapy (transform_osm.py, PBF→PBF)"
TRASKA_APP_URL="${TRASKA_APP_URL}" \
CRON_SECRET="${CRON_SECRET:-}" \
SYNTHETIC_WAYS_MANIFEST="${SYNTHETIC_WAYS_MANIFEST}" \
GRAPH_INTERVENTIONS_SNAPSHOT="${GRAPH_INTERVENTIONS_SNAPSHOT}" \
STRIP_ACCESS_TAGS="${STRIP_ACCESS_TAGS:-true}" \
    "${VENV_PYTHON}" "${SCRIPT_DIR}/transform_osm.py" "${PBF_FILE}" "${MAP_PROCESSED}"
[ -f "${MAP_PROCESSED}" ] || { err "Brak ${MAP_PROCESSED} po transform_osm"; exit 1; }
log "✓ Przetworzono: $(du -h "${MAP_PROCESSED}" | cut -f1)"

# Surowy PBF nam już niepotrzebny — wolimy odzyskać miejsce
rm -f "${PBF_FILE}"

# ============================== 3. Build grafów ==============================

step "3/5 Build grafów (ors-builder)"
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

step "4/5 Atomic swap"

# Usuń pozostałości po poprzednim udanym biegu (gdyby cleanup się nie wykonał)
rm -rf "${GRAPHS_OLD}"

# Po tym punkcie zaczyna się stan "świat zmieniony" — rollback aktywny
SWAPPED=1

if [ -d "${GRAPHS_DIR}" ]; then
    mv "${GRAPHS_DIR}" "${GRAPHS_OLD}"
fi
mv "${GRAPHS_STAGING}" "${GRAPHS_DIR}"
log "✓ graphs → graphs_old, graphs_staging → graphs"

if [ -f "${PROD_MAP}" ]; then
    mv "${PROD_MAP}" "${PROD_MAP_BACKUP}"
fi
mv "${MAP_PROCESSED}" "${PROD_MAP}"
log "✓ mazowieckie.osm.pbf podmieniony (backup w .prev)"

# ============================== 5. Restart ors-app ===========================

step "5/5 Restart ors-app na nowych grafach"

# UWAGA: to NIE jest zero-downtime. `docker rollout` wymaga usługi bez
# container_name i bez sztywno publikowanego portu (druga replika musi móc
# wstać obok) — taką topologię (traefik przed usługami) ma gałąź
# `zero_down_time` (commit "Zero downtime konfiguracja"), która nigdy nie
# weszła na main. Na mainie ors-app ma container_name=ors-app i port
# 8080:8082, więc rollout kończył się konfliktem nazwy — tym bardziej, że
# produkcyjny kontener bywał startowany z compose SUBMODUŁU (inny projekt
# compose), przez co rollout w ogóle nie widział "swojej" usługi.
#
# Robimy więc deterministyczny recreate: zdejmij DOWOLNY kontener o nazwie
# ors-app (niezależnie od projektu compose, który go stworzył), postaw z ROOT
# compose i czekaj na ready. Okno niedostępności = czas ładowania grafu
# (zwykle 1-3 min). Prawdziwy zero-downtime wróci po merge gałęzi zero_down_time.
# Bezpiecznik obrazu: ors-app MUSI chodzić na obrazie lokalnego forka —
# dokładnie tym, którym builder zbudował grafy. Awaria 2026-07-09: root compose
# wskazywał tag upstreamowy, docker ściągnął czysty obraz z Docker Huba (bez
# profilu driving-bus) i ors-app wpadł w crash-loop.
if ! docker compose -f "${ROOT_COMPOSE_FILE}" config ors-app 2>/dev/null \
       | grep -q 'image: local/openrouteservice:v9.4.0'; then
    err "ROOT compose nie wskazuje obrazu local/openrouteservice:v9.4.0 dla ors-app"
    err "(ors-app zbudowałby się z innego obrazu niż grafy — przerwano PRZED restartem)"
    exit 1
fi

if docker ps -a --format '{{.Names}}' | grep -q '^ors-app$'; then
    log "Zdejmuję istniejący kontener ors-app (niezależnie od projektu compose)..."
    docker rm -f ors-app >/dev/null
fi
# Od tego momentu stary kontener nie istnieje — rollback MUSI odtworzyć ors-app.
REPLACED=1
log "Startuję ors-app z ROOT compose..."
docker compose -f "${ROOT_COMPOSE_FILE}" up -d ors-app

log "Czekam na ready przez ${ORS_APP_HEALTH_URL} (timeout ${ORS_APP_TIMEOUT}s — ładowanie grafu)..."
start_ts=$(date +%s)
while true; do
    elapsed=$(( $(date +%s) - start_ts ))
    if [ ${elapsed} -gt ${ORS_APP_TIMEOUT} ]; then
        err "ors-app nie odpowiada 'ready' po ${elapsed}s od restartu"
        docker logs --tail=40 ors-app 2>&1 | tail -20 >&2 || true
        exit 1
    fi

    # Fail-fast na crash-loop: restart-count rośnie = kontener umiera przy
    # starcie (np. zły obraz/config) — nie ma sensu czekać do timeoutu.
    restarts=$(docker inspect -f '{{.RestartCount}}' ors-app 2>/dev/null || echo 0)
    if [ "${restarts:-0}" -ge 2 ]; then
        err "ors-app w crash-loopie (RestartCount=${restarts}) — logi:"
        docker logs --tail=40 ors-app 2>&1 | tail -20 >&2 || true
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
#
# BEZPIECZNIK (2026-07-12): baked wolno wysłać TYLKO gdy transform pobrał definicje
# z ŻYWEGO rejestru ("source":"rejestr" w manifeście). Wypiek ze snapshotu/bootstrapu
# może nieść nieaktualne definicje — oznaczenie ich BAKED kłamało (case: stara wersja
# way'a 9990000002 wypieczona ze snapshotu po chwilowym restarcie aplikacji web,
# a rejestr twierdził, że w grafie jest nowa).
if [ -f "${SYNTHETIC_WAYS_MANIFEST}" ] \
       && ! grep -q '"source": *"rejestr"' "${SYNTHETIC_WAYS_MANIFEST}"; then
    err "Transform NIE pobrał interwencji z żywego rejestru (snapshot/bootstrap)."
    err "Graf może zawierać nieaktualne definicje — pomijam callback baked."
    err "Sprawdź aplikację web (:3000) i powtórz refresh, żeby wypiec świeży rejestr."
    report_log WARN "Migration completed with stale interventions (snapshot/bootstrap)"
elif [ -f "${SYNTHETIC_WAYS_MANIFEST}" ] && [ -n "${CRON_SECRET:-}" ]; then
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
rm -f  "${PROD_MAP_BACKUP}"
rm -rf "${STAGING_DIR}"
# Artefakty ewentualnych poprzednich NIEUDANYCH biegów — po sukcesie zbędne.
rm -rf "${GRAPHS_DIR}.failed"
rm -f  "${PROD_MAP}.failed"
log "✓ Usunięto staging, backupy i artefakty .failed"

# Świat jest spójny — wyłączamy rollback z trapa
SWAPPED=0

report_log INFO "Migration completed"
step "SUKCES — refresh ORS zakończony"
