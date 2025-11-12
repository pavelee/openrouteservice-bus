## Skrypt modyfikacji mapy OSM

### Wygenerowanie pliku mapy

```
chmod +x ./convert_osm_to_xml.py
```

sciagamy plik z https://download.geofabrik.de/europe/poland/mazowieckie.html i nazywamy go mazowieckie-latest.osm.pbf

```
./convert_osm_to_xml.py
```


### Usunięcie zamkniętych ulic dla ruchu (np. Nowy świat)

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

