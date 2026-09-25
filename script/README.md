# Skrypty mapy OSM i refreshu ORS

## Pełen refresh produkcji (ZALECANE) — `refresh-ors.sh`

Jeden skrypt który robi wszystko: pobiera świeży PBF, nakłada transformacje
mapy (interwencje z rejestru), **buduje nowe grafy ORS w izolowanym kontenerze**
(`ors-builder` z profilu compose), przenosi je do katalogu **nieaktywnej** kopii
ORS i przełącza na nią ruch bez przerwy (`scripts/ors-switch.sh` w repo Traski).

Kopie ORS żyją w `docker-compose.yml` repo Traski: `ors_blue` czyta graf
z `ors-docker/graphs`, `ors_green` z `ors-docker/graphs_green`. Działa jedna,
proxy Caddy kieruje ruch na tę zdrową. Usługa `ors-app` z compose tego
submodułu NIE jest produkcją (zajmuje port 8080), nie uruchamiaj jej obok.

```bash
# Uruchomienie (z dowolnego cwd, skrypt sam ustala ścieżki)
./script/refresh-ors.sh
```

Zachowanie:
- Lock w `/tmp/traska-refresh-ors.lock`, dwie instancje równolegle nie pójdą.
- Pliki staging w `ors-docker/files/staging/` i grafy w `ors-docker/graphs_staging/`.
- Nowa kopia ładuje graf obok działającej. Stara gaśnie dopiero, gdy nowa jest zdrowa.
- Po sukcesie: czyści staging, kasuje `mazowieckie.osm.pbf.prev`. Graf poprzedniej
  kopii zostaje, więc cofnięcie to `scripts/ors-switch.sh` (w repo Traski).
- Po błędzie przełączenia: ruch obsługuje stara kopia, nowy graf ląduje w
  `graphs*.failed`, mapa wraca z `.prev` (błędna w `mazowieckie.osm.pbf.failed`).
- Po błędzie przed podmianą: produkcja nietknięta.
- Przerywa przed buildem, gdy ORS z compose Traski montuje inny katalog
  `ors-docker` niż ten, do którego pisze builder (`TRASKA_ORS_DIR`).

Wymagania: `docker`, `wget`, lokalny venv pod `script/env/` z modułem `osmium`.
Skrypt uruchamia się na hoście z katalogu repo Traski, nie w kontenerze (woła `docker compose`
ze ścieżkami hosta; sam stawia kontener `ors-builder`).

### Przygotowanie maszyny (raz)

```bash
cd openrouteservice-bus
python3 -m venv script/env
script/env/bin/pip install -r script/requirements.txt
script/env/bin/python -c 'import osmium'     # bez błędu = gotowe
command -v wget || brew install wget
```

- `requirements.txt` to tylko `osmium` (`lxml` i `requests` nie są potrzebne: skrypt używa
  biblioteki standardowej).
- `osmium` 4.0.2 ma gotowe paczki dla Pythona do 3.13, 4.3.1 także dla 3.14. Gdy pip zaczyna
  kompilować (`Building wheel for osmium`), brakuje paczki dla tej wersji Pythona: albo
  `python3.13 -m venv script/env`, albo `brew install cmake boost` i powtórka.
- Brak venv kończy się w preflight komunikatem „Brak python3 w venv” z tym poleceniem.

Na macmini pierwszy raz 2026-09-25: preflight padł na braku venv, po przygotowaniu cały
refresh trwał 6 min 49 s (pobranie 4 s, transformacja 255 s, build grafu 121 s, przełączenie
ORS 23 s).

Czas na macmini: około 7 min (pierwszy refresh 2026-09-25). Można odpalić w tle:
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
