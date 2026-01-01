## Skrypt modyfikacji mapy OSM

### Automatyczna aktualizacja mapy (ZALECANE)

Nowy zautomatyzowany skrypt który wykonuje cały proces aktualizacji mapy:

```bash
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

