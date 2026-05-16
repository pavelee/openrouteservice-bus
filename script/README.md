## Skrypt modyfikacji mapy OSM

### Pełen refresh produkcji (ZALECANE) — `refresh-ors.sh`

Jeden skrypt który robi wszystko: pobiera świeży PBF, konwertuje, poprawia
prywatne drogi, **buduje nowe grafy ORS w izolowanym kontenerze** (`ors-builder`
z profilu compose), robi atomic swap katalogów i restartuje `ors-app`. Stary
ors-app serwuje przez cały czas builda — downtime tylko podczas finalnego
restartu (~30–60 s).

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

### Aktualizacja samego pliku XML — `update_osm.py`

Skrypt który automatyzuje tylko fazę przygotowania danych OSM (bez buildu
grafów i bez restartu kontenera). Przydatny gdy chcesz wymienić sam plik
`mazowieckie.osm` ręcznie — pełen refresh produkcji robi `refresh-ors.sh`.

```bash
# uruchomienie środowiska python
source env/bin/activate

# Podstawowe użycie - wykonuje cały proces automatycznie
./update_osm.py

# Z szczegółowymi logami
./update_osm.py --verbose

# Test run (pokazuje co zostanie zrobione bez wykonania)
./update_osm.py --dry-run
```

Skrypt automatycznie:
1. Pobiera najnowsze dane z https://download.geofabrik.de/europe/poland/mazowieckie-latest.osm.pbf
2. Konwertuje PBF do XML używając `convert_osm_to_xml.py`
3. Przetwarza prywatne drogi używając `fix_private_roads.py`
4. Tworzy backup i zastępuje plik w `../ors-docker/files/mazowieckie.osm`
5. Czyści pliki tymczasowe

### Manualna aktualizacja mapy (legacy)

#### Wygenerowanie pliku mapy

```
chmod +x ./convert_osm_to_xml.py
```

sciagamy plik z https://download.geofabrik.de/europe/poland/mazowieckie.html i nazywamy go mazowieckie-latest.osm.pbf

```
./convert_osm_to_xml.py
```

#### Usunięcie zamkniętych ulic dla ruchu (np. Nowy świat)

```
chmod +x ./fix_private_roads.py
```

```
source env/bin/activate
```

```
./fix_private_roads.py mazowieckie-latest.osm mazowieckie.osm
```

### poprawki do wdrożenia 

Linia: 491365793 oraz 171028660 dodanie tagu:
<tag k="maxwidth" v="0.5"/>

