#!/usr/bin/env python3
"""DEPRECATED (2026-07-08): zastąpiony przez transform_osm.py (PyOsmium, PBF→PBF).
Trzymany wyłącznie jako referencja dla test_transform_equivalence.py do czasu
pierwszego zwalidowanego rebuilda nowym pipeline (potem do usunięcia).


Script to process large OSM files and modify private residential roads to tertiary highways.
This script uses lxml for efficient XML parsing with low memory usage and ensures valid XML output.
"""

import sys
import os
import json
import subprocess
import time
import urllib.request
from lxml import etree

# Syntetyczne way'e dokładane do mapy — tymczasowa organizacja ruchu, której NIE MA w OSM.
# Miasto przy remontach otwiera "kanały nawrotki" (przejazd przez pas rozdzielający),
# fizycznie nieistniejące na mapie. Way dostaje sztuczne ID (pula 999xxxxxxx, poza zakresem
# realnych OSM) i MUSI referować ISTNIEJĄCE węzły obu jezdni (nd), inaczej graf się nie
# połączy. Wstrzykiwane przed pierwszą <relation> (czyli po wszystkich nodach i way'ach).
#
# ŹRÓDŁO PRAWDY: rejestr interwencji routingowych w aplikacji web
# (GET /api/routing-interventions/synthetic-ways, patrz load_synthetic_ways() i projekt
# memory/routing-interventions-design.md). Nawrotki są self-gating (router nie bierze
# zawrotki, gdy przejazd prosty otwarty), więc siedzą w grafie NA STAŁE — okna czasowe
# remontów obsługuje aplikacja per request przez options.avoid_polygons, BEZ rebuildu.
#
# Lista poniżej to bootstrap/awaryjny fallback, gdy API nie odpowiada podczas refreshu —
# krytyczne nawrotki nie mogą zniknąć z grafu, bo aktywne zamknięcia na nich polegają.
#
# 9990000001 — zawrotka za peronem MUZEUM NARODOWE 06 (remont torowiska na przejazdach
#   przez Rondo de Gaulle'a, WTP 6-12.07.2026; samo zamknięcie ronda = rekord CLOSURE
#   w rejestrze, NIE w tym skrypcie). Jezdnia wsch. Al. Jerozolimskich (węzeł 2309309019
#   na way 229399403) → jezdnia zach. (węzeł 10615716693 na way 229399405), ~21 m przez
#   pas rozdzielający. psv=yes → bus$preferred, bez kar.
SYNTHETIC_WAYS = [
    {
        'id': '9990000001',
        'nds': ['2309309019', '10615716693'],
        'tags': {
            'highway': 'tertiary',
            'oneway': 'yes',
            'psv': 'yes',
            'name': 'Zawrotka za peronem Muzeum Narodowe 06 (remont Rondo de Gaulle\'a)',
        },
    },
]

# Trwałe blokady jakościowe (WAY_BLOCK w rejestrze interwencji) — way'e wycinane
# z grafu przez highway=construction. ŹRÓDŁO PRAWDY: rejestr (panel "Interwencje").
# Lista poniżej to bootstrap/awaryjny fallback jak SYNTHETIC_WAYS — przy niedostępnym
# API blokady jakościowe NIE MOGĄ zniknąć z grafu (127 wróciłby na Wiślaną itd.).
# Uzasadnienia per ulica: pole `notes` rekordów w rejestrze.
WAY_BLOCKS_BOOTSTRAP = {
    '20930779',                              # Wiślana (127)
    '33276900',                              # Zagłoby (187)
    '308031464',                             # Złota (504)
    '341151409',                             # Kościuszki (817)
    '888011097', '174143991', '386852929',   # Wiejska (131)
    '860371908',                             # Tokarzewskiego-Karaszewicza (128)
    '29571422',                              # Zawiszaków (115, za ciasna)
    '34982097',                              # Gimnazjalna (129)
    '206528330',                             # Rezedowa (402)
    '114895531',                             # Wyczółki (331)
    '506254774', '491365793',                # serwisówki-skróty
}

def load_graph_interventions():
    """Pobiera interwencje grafowe z rejestru (aplikacja web, GET /graph-export):
    syntetyczne way'e (nawrotki) + trwałe blokady way_id. Skleja z bootstrapami
    (dedup, rejestr wygrywa). Zapisuje manifest interventionId do pliku wskazanego
    przez SYNTHETIC_WAYS_MANIFEST (refresh-ors.sh POST-uje go na /graph-export/baked
    po udanym rollout). Błąd/API niedostępne -> tylko bootstrap, NIE przerywa builda
    (wzorzec load_bus_route_way_ids)."""
    ways = {w['id']: w for w in SYNTHETIC_WAYS}
    block_way_ids = set(WAY_BLOCKS_BOOTSTRAP)
    intervention_ids = []
    app_url = os.environ.get('TRASKA_APP_URL', 'http://localhost:3000')
    secret = os.environ.get('CRON_SECRET')
    if not secret:
        print('WARN: brak CRON_SECRET w env — interwencje grafowe tylko z bootstrapu')
    else:
        try:
            req = urllib.request.Request(
                f'{app_url}/api/routing-interventions/graph-export',
                headers={'Authorization': f'Bearer {secret}'},
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                payload = json.load(resp)
            for entry in payload.get('syntheticWays', []):
                way_def = entry.get('wayDef') or {}
                if way_def.get('id') and way_def.get('nds') and way_def.get('tags'):
                    ways[way_def['id']] = way_def
                    intervention_ids.append(entry.get('interventionId'))
            for entry in payload.get('wayBlocks', []):
                ids = entry.get('wayIds') or []
                if ids:
                    block_way_ids.update(str(i) for i in ids)
                    intervention_ids.append(entry.get('interventionId'))
            print(f"✓ Loaded z rejestru interwencji: {len(payload.get('syntheticWays', []))} "
                  f"synthetic ways, {len(payload.get('wayBlocks', []))} way-block rekordów "
                  f"({len(block_way_ids)} way_id do zablokowania)")
        except Exception as e:
            print(f'WARN: rejestr interwencji niedostępny ({e}) — interwencje grafowe tylko z bootstrapu')

    manifest_path = os.environ.get('SYNTHETIC_WAYS_MANIFEST')
    if manifest_path:
        try:
            with open(manifest_path, 'w') as f:
                json.dump({'ids': [i for i in intervention_ids if isinstance(i, int)]}, f)
            print(f'✓ Manifest baked zapisany: {manifest_path}')
        except Exception as e:
            print(f'WARN: nie udało się zapisać manifestu baked: {e}')

    return list(ways.values()), block_way_ids

def load_bus_route_way_ids():
    """Wczytuje zbiór way_id z PostGIS bus_route_ways (cron/osm2pgsql/import/bus_routes.lua) —
    way'e będące częścią jakiejkolwiek relacji OSM route=bus. Czytane przez isOnBusRoute()
    w BusFlagEncoder.java (tag bus:on_route=yes), zastępuje heurystykę "maxspeed otagowany
    ⇒ to nie skrót" w custom_model realnym sygnałem. Błąd/brak tabeli -> zbiór pusty,
    NIE przerywa builda (ten sam wzorzec "nieblokujące" jak compute-bus-corridors.sql
    w cron/import-script.sh)."""
    try:
        out = subprocess.run(
            ["docker", "exec", "-e", "PGPASSWORD=o2p", "web-postgis-1",
             "psql", "-h", "localhost", "-U", "o2p", "-d", "o2p",
             "-t", "-A", "-c", "SELECT DISTINCT way_id FROM bus_route_ways;"],
            capture_output=True, text=True, timeout=60,
        )
        if out.returncode != 0:
            print(f"WARN: bus_route_ways query failed (rc={out.returncode}): {out.stderr.strip()}")
            return set()
        ids = {line.strip() for line in out.stdout.splitlines() if line.strip()}
        print(f"✓ Loaded {len(ids)} bus_route_ways way_ids from PostGIS")
        return ids
    except Exception as e:
        print(f"WARN: bus_route_ways query failed non-fatally: {e}")
        return set()

def process_osm_file(input_file, output_file, skip_relations=None, bus_route_way_ids=None,
                     synthetic_ways=None, block_way_ids=None):
    """Process OSM file using streaming parsing to ensure valid XML output."""

    if skip_relations is None:
        skip_relations = []
    if bus_route_way_ids is None:
        bus_route_way_ids = set()
    if synthetic_ways is None:
        synthetic_ways = list(SYNTHETIC_WAYS)
    if block_way_ids is None:
        block_way_ids = set(WAY_BLOCKS_BOOTSTRAP)
    
    # Check if input file exists
    if not os.path.isfile(input_file):
        print(f"Error: Input file '{input_file}' does not exist.")
        return False
    
    # Nadpisujemy istniejący plik wyjściowy bez pytania — skrypt biegnie w
    # automatyzacji (refresh-ors.sh); interaktywny prompt wieszał pipeline.
    if os.path.isfile(output_file):
        print(f"Output file '{output_file}' already exists — overwriting.")
    
    # Get file size for progress reporting
    file_size = os.path.getsize(input_file)
    print(f"Processing OSM file: {input_file}")
    print(f"Output will be saved to: {output_file}")
    print(f"Input file size: {file_size / (1024*1024):.2f} MB")
    
    # Count ways that will be modified
    modified_ways = 0
    total_elements = 0
    start_time = time.time()
    
    try:
        # Create a custom handler to process the XML
        class OSMHandler:
            def __init__(self, output_file, bus_route_way_ids=None, synthetic_ways=None,
                         block_way_ids=None):
                self.output_file = output_file
                self.bus_route_way_ids = bus_route_way_ids or set()
                self.synthetic_ways = synthetic_ways or []
                self.block_way_ids = block_way_ids or set()
                self.blocked_count = 0
                # Guard na reprocessing już przetworzonego pliku: way'e syntetyczne
                # obecne w wejściu nie są wstrzykiwane ponownie (duplikat ID w OSM).
                self.synthetic_ids_in_input = set()
                self.current_way = None
                self.current_relation = None
                self.in_way = False
                self.in_relation = False
                self.is_private = False
                self.is_residential = False
                self.modified_count = 0
                self.skipped_relations = 0
                self.depth = 0
                self.synthetic_ways_written = False
                
                # 1963216 - zakręt w lewo w piękną (obok sejmu) np. 131
                # 9166265 - no_left_turn Patriotów(377954285)->Bysławska(507958952) via węzeł 4973651213.
                #   Linia 229 / route 481510, między PKP Falenica 59 a 57: blokuje skręt z Patriotów wprost
                #   w Bysławska, przez co router jedzie dalej Patriotów i pętli (z bearingami produkcyjnymi
                #   przez Ciepielowską/Malczycką — zawrotka na ciasnej ulicy). Restrykcja z 2018, kierowca
                #   potwierdza że skręt jest przejezdny dla autobusu → usuwamy (jak 1963216).
                # 18888466 - no_left_turn Plac Powstańców Warszawy(1370881107)->(1120466800) via węzeł 224852315.
                #   Linia 107 / route 481259, między Pl. Powstańców Warszawy 01 a Chmielna 01: blokuje
                #   przejazd PROSTO na południe przez plac (oba way to "Plac Powstańców Warszawy", ruch
                #   wprost/lekko w lewo). Bez fixu router omija plac Świętokrzyska->Jasna->Brokla->Szpitalna
                #   (770 m) zamiast jechać prosto Mazowiecka->przez plac (448 m). Brak except=bus, więc
                #   dotyczy też autobusu; linia 107 realnie jeździ prosto przez plac → usuwamy.
                # Lista relacji do pominięcia - domyślnie powyższe + dodatkowe z parametru
                # 7783785 - no_left_turn dla Białobrzeska, 154, autobus wyraźnie może tam jeździć jak chce :D 
                # 20253836 - zakręt na stawki w lewo.. 157 może wszystko jak zawsze wtf
                self.skip_relations = ['1963216', '9166265', '18888466', '7783785', '20253836'] + skip_relations
                
                # Open output file
                self.out = open(output_file, 'wb')
                # Write XML declaration
                self.out.write(b'<?xml version="1.0" encoding="UTF-8"?>\n')
            
            def start_element(self, name, attrs):
                self.depth += 1
                
                # Handle root element
                if self.depth == 1:
                    self.out.write(f'<{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                    return
                
                # Handle way elements
                if name == 'way':
                    self.in_way = True
                    self.current_way = attrs.get('id', '')
                    self.is_private = False
                    self.is_residential = False

                    if any(sw['id'] == self.current_way for sw in self.synthetic_ways):
                        self.synthetic_ids_in_input.add(self.current_way)
                    
                    # Write way opening tag with attributes
                    self.out.write(f'  <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                
                # Handle relation elements
                elif name == 'relation':
                    # Relacje idą po way'ach — to ostatni moment na dołożenie
                    # syntetycznych way'ów (SYNTHETIC_WAYS), których nie ma w OSM.
                    if not self.synthetic_ways_written:
                        self._write_synthetic_ways()

                    self.in_relation = True
                    self.current_relation = attrs.get('id', '')
                    
                    # Check if this relation should be skipped
                    if self.current_relation in self.skip_relations:
                        print(f"Skipping relation ID: {self.current_relation}")
                        self.skipped_relations += 1
                        return  # Skip this relation entirely
                    
                    # Write relation opening tag with attributes
                    self.out.write(f'  <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
                
                # Handle tags within ways
                elif self.in_way and name == 'tag':
                    k = attrs.get('k', '')
                    v = attrs.get('v', '')

                    # Trwałe blokady jakościowe z rejestru interwencji (WAY_BLOCK)
                    # + bootstrap WAY_BLOCKS_BOOTSTRAP: way wycinany z grafu przez
                    # highway=construction (pozostałe tagi zostają bez zmian).
                    # Uzasadnienia per ulica: pole `notes` rekordu w rejestrze.
                    if self.current_way in self.block_way_ids and k == 'highway':
                        self.out.write(f'    <{name} k="highway" v="construction"/>\n'.encode('utf-8'))
                        self.blocked_count += 1
                        return

                    # Linia 409 / św. Wincentego przy Metro Kondratowicza: jezdnia rozdzielona ma
                    # zerwaną nitkę północną — łącznik 1453889955 (~17 m) jest oneway=yes na południe,
                    # więc autobus jadący na północ robił objazd 20 Dyw. Piechoty WP + zawrotkę.
                    # Dodajemy oneway:bus=no — BusFlagEncoder.isOneway() zwalnia odcinek z jednokierunkowości
                    # tylko dla profilu driving-bus (ruch samochodowy zostaje jednokierunkowy).
                    if self.current_way in ['1453889955'] and k == 'highway':
                        # zapisz oryginalny tag highway bez zmian
                        self.out.write(f'    <{name}'.encode('utf-8'))
                        for attr_name, attr_value in attrs.items():
                            self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                        self.out.write(b'/>\n')
                        # dołóż wyjątek oneway dla autobusu
                        self.out.write(b'    <tag k="oneway:bus" v="no"/>\n')
                        return

                    # Linia Z33 / route 505680, Rondo ONZ 01 → Dw. Centralny 23: wjazd na podziemny
                    # ślimak (bus-only rampa do zatoki przystankowej) ma 3 pierwsze odcinki BEZ
                    # psv=yes (27569980 62 m, 307888832 44 m, 30611690 78 m), choć kontynuacja tej
                    # samej rampy (128214245→128214247→30806163, kończy się dokładnie w punkcie
                    # przystanku) JEST otagowana psv=yes. Reguła custom_modelu
                    # `road_class==SERVICE && bus$preferred==false → ×0.1` (chroni psv-tagowane
                    # zatoki, np. ta sama rampa dla linii 504) penalizuje też te 3 odcinki, bo nie
                    # mają tagu — ważony koszt rampy (619 m realnie) wychodzi wyżej niż objazd
                    # powierzchniowy przez Aleje Jerozolimskie/Chałubińskiego (802 m), więc router
                    # robi nawrotkę na skrzyżowaniu zamiast skręcić w ślimaka. Dotagowanie psv=yes
                    # dorównuje je do reszty rampy — bez zmiany w custom_model.
                    if self.current_way in ['27569980', '307888832', '30611690'] and k == 'highway':
                        self.out.write(f'    <{name}'.encode('utf-8'))
                        for attr_name, attr_value in attrs.items():
                            self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                        self.out.write(b'/>\n')
                        self.out.write(b'    <tag k="psv" v="yes"/>\n')
                        return

                    # Linia 106 / route 481257, Grzybowska (między Al. Jana Pawła II a Wronią):
                    # way 116934893 (~27 m, odcinek Waliców→Pereca) jest narysowany w ODWROTNEJ
                    # kolejności węzłów niż sąsiednie segmenty Grzybowskiej, ale ma ten sam oneway=-1.
                    # Skutek: ten jeden odcinek daje przejazd eastbound "pod prąd" w środku
                    # jednokierunkowego korytarza westbound (sąsiedzi oneway=-1 rysowani W→E oraz
                    # oneway=yes rysowani E→W są wszyscy westbound). Autobus jadący na zachód nie może
                    # przejechać i robi objazd Waliców→Pereca→Grzybowska. Eastbound w tym korytarzu jest
                    # i tak niemożliwy (potwierdzone routingiem), więc korytarz jest jednokierunkowy
                    # westbound. Geometria 116934893 to 8842508338(wschód)→4298291164(zachód), zatem
                    # oneway=yes = przejazd east→west = westbound, spójny z resztą Grzybowskiej.
                    if self.current_way in ['116934893'] and k == 'oneway':
                        self.out.write(b'    <tag k="oneway" v="yes"/>\n')
                        return
                    
                    # Tag systemowy: way jest częścią ≥1 relacji OSM route=bus (bus_route_ways,
                    # zasilane przez cron/osm2pgsql/import/bus_routes.lua). Czytany przez
                    # BusFlagEncoder.isOnBusRoute() -> EV bus$on_route, zwalnia z kary
                    # "osiedlowe skróty" w orsBusCustomModel.ts. Systemowy zamiennik dla ręcznych
                    # fixów maxspeed per way-ID (np. Orląt Lwowskich, linia 187 wyżej) — te
                    # zostają jako bezpieczne no-opy, nowe przypadki już nie wymagają wpisu tutaj.
                    if (self.current_way in self.bus_route_way_ids) and k == 'highway':
                        self.out.write(f'    <{name}'.encode('utf-8'))
                        for attr_name, attr_value in attrs.items():
                            self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                        self.out.write(b'/>\n')
                        self.out.write(b'    <tag k="bus:on_route" v="yes"/>\n')
                        return

                    # Skip access=private tags
                    if k == 'access' and v == 'private':
                        self.is_private = True
                        return  # Skip writing this tag

                    if k == 'access' and v == 'no':
                        self.is_private = True
                        return  # Skip writing this tag

                    # zdejmujemy wszystkie remonty dla minimalizacji anomalii
                    # if k == 'highway' and v == 'construction':
                    #     self.out.write(f'    <{name} k="highway" v="secondary"/>\n'.encode('utf-8'))
                    #     return   
                    # if k == 'construction':
                    #     return  # Skip writing this tag  
                    
                    # Write other tags normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle tags within relations
                elif self.in_relation and name == 'tag':
                    # Skip all tags for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    # Write relation tags normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle members within relations
                elif self.in_relation and name == 'member':
                    # Skip all members for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    # Write relation members normally
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle nd references within ways
                elif self.in_way and name == 'nd':
                    self.out.write(f'    <{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'/>\n')
                
                # Handle all other elements
                else:
                    indent = '  ' * (self.depth - 1)
                    self.out.write(f'{indent}<{name}'.encode('utf-8'))
                    for attr_name, attr_value in attrs.items():
                        self.out.write(f' {attr_name}="{self._escape_attr(attr_value)}"'.encode('utf-8'))
                    self.out.write(b'>\n')
            
            def end_element(self, name):
                # Handle way closing
                if name == 'way' and self.in_way:
                    self.in_way = False
                    self.out.write(b'  </way>\n')
                    
                    # Count modified ways
                    if self.is_private or self.is_residential:
                        self.modified_count += 1
                
                # Handle relation closing
                elif name == 'relation' and self.in_relation:
                    self.in_relation = False
                    
                    # Skip closing tag for skipped relations
                    if self.current_relation in self.skip_relations:
                        return
                    
                    self.out.write(b'  </relation>\n')
                
                # Handle root element
                elif self.depth == 1:
                    # Fallback: plik bez relacji — dołóż syntetyczne way'e przed zamknięciem roota
                    if not self.synthetic_ways_written:
                        self._write_synthetic_ways()
                    self.out.write(f'</{name}>'.encode('utf-8'))
                
                # Handle other elements (not way, nd, tag, member)
                elif not (self.in_way and (name == 'nd' or name == 'tag')) and not (self.in_relation and (name == 'member' or name == 'tag')):
                    indent = '  ' * (self.depth - 1)
                    self.out.write(f'{indent}</{name}>\n'.encode('utf-8'))
                
                self.depth -= 1
            
            def handle_text(self, content):
                # Handle text content (rare in OSM files)
                if content and content.strip():
                    indent = '  ' * self.depth
                    self.out.write(f'{indent}{self._escape_text(content)}\n'.encode('utf-8'))
            
            def _write_synthetic_ways(self):
                """Dokłada way'e z SYNTHETIC_WAYS (tymczasowe kanały nawrotki itp.).
                Węzły (nd) muszą już istnieć w pliku — referujemy istniejące node'y OSM."""
                for sw in self.synthetic_ways:
                    if sw['id'] in self.synthetic_ids_in_input:
                        print(f"Skip synthetic way {sw['id']} — już obecny w pliku wejściowym")
                        continue
                    self.out.write(f'  <way id="{sw["id"]}" version="1">\n'.encode('utf-8'))
                    for nd in sw['nds']:
                        self.out.write(f'    <nd ref="{nd}"/>\n'.encode('utf-8'))
                    for k, v in sw['tags'].items():
                        self.out.write(f'    <tag k="{self._escape_attr(k)}" v="{self._escape_attr(v)}"/>\n'.encode('utf-8'))
                    self.out.write(b'  </way>\n')
                    print(f"Injected synthetic way {sw['id']} ({sw['tags'].get('name', 'bez nazwy')})")
                self.synthetic_ways_written = True

            def close(self):
                if hasattr(self, 'out') and self.out:
                    self.out.close()
            
            def _escape_attr(self, text):
                """Escape XML attribute values."""
                if not isinstance(text, str):
                    text = str(text)
                return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
            
            def _escape_text(self, text):
                """Escape XML text content."""
                if not isinstance(text, str):
                    text = str(text)
                return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
        
        # Create our handler
        handler = OSMHandler(output_file, bus_route_way_ids=bus_route_way_ids,
                             synthetic_ways=synthetic_ways, block_way_ids=block_way_ids)
        
        # Print information about relations to be skipped
        if handler.skip_relations:
            print(f"Relations to be skipped: {', '.join(handler.skip_relations)}")
        
        # Use iterparse for streaming processing
        print("Starting XML parsing...")
        context = etree.iterparse(input_file, events=('start', 'end'))
        
        # Process the XML file
        for i, (event, elem) in enumerate(context):
            # Update element counter and show progress
            if event == 'start':
                total_elements += 1
                if total_elements % 100000 == 0:
                    elapsed = time.time() - start_time
                    print(f"Progress: Processed {total_elements:,} elements in {elapsed:.1f} seconds")
                    print(f"Modified ways so far: {handler.modified_count}")
            
            # Process the element
            if event == 'start':
                # Convert lxml Element to attributes dict
                attrs = dict(elem.attrib)
                handler.start_element(elem.tag, attrs)
                
                # Handle text content
                if elem.text and elem.text.strip():
                    handler.handle_text(elem.text)
            
            elif event == 'end':
                handler.end_element(elem.tag)
                
                # Clear element to save memory
                elem.clear()
                # Also eliminate now-empty references from the root node to elem
                while elem.getprevious() is not None:
                    del elem.getparent()[0]
        
        # Close the handler
        handler.close()
        
        # Update modified_ways count
        modified_ways = handler.modified_count
        
        # Report statistics
        elapsed_time = time.time() - start_time
        print("\nProcessing complete!")
        print("Statistics:")
        print(f"  - Total elements processed: {total_elements:,}")
        print(f"  - Ways modified: {modified_ways}")
        print(f"  - Ways blocked (WAY_BLOCK): {handler.blocked_count}")
        print(f"  - Relations skipped: {handler.skipped_relations}")
        print(f"  - Processing time: {elapsed_time:.2f} seconds")
        print(f"  - Processing speed: {total_elements / elapsed_time:.2f} elements/second")
        print(f"  - Input file size: {file_size / (1024*1024):.2f} MB")
        print(f"  - Output file size: {os.path.getsize(output_file) / (1024*1024):.2f} MB")
        
        return True
    
    except Exception as e:
        print(f"Error processing file: {str(e)}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <input_osm_file> <output_osm_file> [relation_id1,relation_id2,...]")
        print("Example: python fix_private_roads.py input.osm output.osm 1963216,123456,789012")
        print("The script will always skip relation 1963216 by default")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2]
    
    # Parse additional relations to skip
    additional_skip_relations = []
    if len(sys.argv) > 3:
        relation_ids = sys.argv[3].split(',')
        additional_skip_relations = [rid.strip() for rid in relation_ids if rid.strip()]

    bus_route_way_ids = load_bus_route_way_ids()
    synthetic_ways, block_way_ids = load_graph_interventions()

    if process_osm_file(input_file, output_file, additional_skip_relations, bus_route_way_ids,
                        synthetic_ways, block_way_ids):
        print("OSM file processing completed successfully.")
    else:
        print("OSM file processing failed.")
        sys.exit(1)
