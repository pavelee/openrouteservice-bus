# Skrypty mapy OSM i refreshu ORS

## Pełen refresh produkcji (ZALECANE) — `refresh-ors.sh`

Jeden skrypt który robi wszystko: pobiera świeży PBF, konwertuje, nakłada
transformacje mapy (interwencje z rejestru), **buduje nowe grafy ORS w
izolowanym kontenerze** (`ors-builder` z profilu compose), robi atomic swap
katalogów i przełącza `ors-app` bez przerwy w serwowaniu (docker rollout).

```bash
# Uruchomienie (z dowolnego cwd — skrypt sam ustala ścieżki)
./script/refresh-ors.sh
```

Zachowanie:
- Lock w `/tmp/traska-refresh-ors.lock` — dwie instancje równolegle nie pójdą.
- Pliki staging w `ors-docker/files/staging/` i grafy w `ors-docker/graphs_staging/`.
- Po sukcesie: czyści staging + `graphs_old`, kasuje `mazowieckie.osm.prev`.
- Po błędzie **po swapie**: automatyczny rollback do `graphs_old` i `.prev`, zostawia `graphs.failed` / `mazowieckie.osm.failed` do inspekcji.
- Po błędzie **przed swapem**: rollbacka nie ma — produkcja nietknięta.

Wymagania: `docker`, `wget`, lokalny venv pod `script/env/` (osmium + lxml).

Czas: ~20–40 min (głównie build grafów). Można odpalić w tle:
`nohup ./script/refresh-ors.sh > refresh.log 2>&1 &`.

## Transformacja mapy — `fix_private_roads.py`

Streaming XML→XML nakładający na surową mapę OSM wszystkie modyfikacje
build-time: blokady way'ów (WAY_BLOCK), syntetyczne way'e (nawrotki), tag
`bus:on_route=yes` (z PostGIS `bus_route_ways`), punktowe poprawki tagów oraz
pominięcie wskazanych relacji turn-restriction. Dane trasowe pochodzą z
REJESTRU INTERWENCJI aplikacji web (`GET /api/routing-interventions/graph-export`)
— w skrypcie zostają tylko bootstrapowe fallbacki na wypadek niedostępności API.

Uruchamiany przez `refresh-ors.sh`; ręcznie:

```bash
source env/bin/activate
./fix_private_roads.py <wejście.osm> <wyjście.osm>
```

## Walidacja tras po zmianach

Harness produkcyjny żyje w `web/` (importuje produkcyjny kod routingu — nie
duplikuje custom_model ani bearingów):

```bash
cd ../../web
npm run validate:route -- <ROUTE_ID> [--smart] [--steps] [--geojson out.geojson]
npm run route:sweep -- --record   # snapshot przed zmianą
npm run route:sweep               # porównanie po zmianie (bramka zero-diff)
npm run test:route-regression     # suita fixtures (baseline'y w repo)
```

Dawny `validate_route.py` (duplikat logiki w Pythonie, dryfował) został
usunięty 2026-07-08 na rzecz powyższego.

## TODO (przeniesione ze starego README)

- Way'e 491365793 i 171028660: rozważane dodanie `maxwidth=0.5` (= wycięcie
  z grafu busa). 491365793 jest już zablokowany jako WAY_BLOCK
  ("serwisówki-skróty"); 171028660 pozostaje do decyzji — jeśli aktualne,
  dodać jako WAY_BLOCK w rejestrze interwencji (panel), nie w kodzie.

## Historia

- `update_osm.py` / `read_osm_map.py` usunięte 2026-07-08 — zastąpione w
  całości przez `refresh-ors.sh` (miały własny, słabszy pipeline bez staging
  i rollbacku).
