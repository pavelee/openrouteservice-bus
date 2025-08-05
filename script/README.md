## Skrypt modyfikacji mapy OSM

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

