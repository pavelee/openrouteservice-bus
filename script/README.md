# Skrypty mapy OSM i refreshu ORS

## Pełen refresh produkcji (ZALECANE) — `refresh-ors.sh`

Jeden skrypt który robi wszystko: pobiera świeży PBF, konwertuje, nakłada
transformacje mapy (interwencje z rejestru), **buduje nowe grafy ORS w
izolowanym kontenerze** (`ors-builder` z profilu compose), robi atomic swap
katalogów i restartuje `ors-app` na nowych grafach (okno ~1-3 min ładowania grafu; zero-downtime wróci po merge gałęzi zero_down_time).

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

## Transformacja mapy — `transform_osm.py`

Jednoprzebiegowa transformacja PBF→PBF (PyOsmium) nakładająca na surową mapę
OSM wszystkie modyfikacje build-time: blokady way'ów (WAY_BLOCK), punktowe
korekty tagów (TAG_OVERRIDE), pominięcia relacji turn-restriction
(RELATION_SKIP), syntetyczne way'e (SYNTHETIC_WAY) i tag `bus:on_route=yes`
(z PostGIS `bus_route_ways`, przez API). Dane pochodzą z REJESTRU INTERWENCJI
aplikacji web (`GET /api/routing-interventions/graph-export`) z fallbackiem:
snapshot ostatniego udanego eksportu → bootstrapy w skrypcie.

Uruchamiany przez `refresh-ors.sh`; ręcznie:

```bash
./env/bin/python3 transform_osm.py <wejście.osm.pbf> <wyjście.osm.pbf>
```

`STRIP_ACCESS_TAGS=false` wyłącza historyczne globalne zdejmowanie
`access=private/no` (Etap 4 planu uproszczenia — semantykę dostępu przejmuje
wtedy BusFlagEncoder). Procedura flipa:
1. `npm run route:sweep -- --record` (web, snapshot przed zmianą),
2. `STRIP_ACCESS_TAGS=false ./script/refresh-ors.sh`,
3. `npm run route:sweep` + `npm run test:route-regression` — każdy regres
   (pętla/przystanek na prywatnym odcinku bez tagu bus) dostaje TAG_OVERRIDE
   (np. `bus=yes`) w rejestrze zamiast globalnej dziury,
4. po stabilizacji ustawić `STRIP_ACCESS_TAGS=false` na stałe w wywołaniu.

Stare skrypty (`fix_private_roads.py`, `convert_osm_to_xml.py`) i test
równoważności usunięte 2026-07-09 po zwalidowanym rebuildzie nowym pipeline
(regresja 116 tras: delty wyłącznie 0/±1 m vs stary graf) — do odzyskania
z historii gita.

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
