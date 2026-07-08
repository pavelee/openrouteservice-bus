#!/usr/bin/env python3
"""
transform_osm.py — jednoprzebiegowa transformacja mapy OSM dla builda grafu ORS
(profil driving-bus). Zastępuje parę convert_osm_to_xml.py + fix_private_roads.py:
czyta PBF (lub XML) i pisze PBF (lub XML — format po rozszerzeniu pliku), bez
wielogigabajtowego XML pośredniego i bez ręcznie pisanego serializera.

Transformacje (kolejność per way):
 1. WAY_BLOCK      — highway=construction (wycięcie z grafu),
 2. TAG_OVERRIDE   — setTags: nadpisz istniejący tag / dołóż brakujący,
 3. strip access   — usunięcie access=private/no (STRIP_ACCESS_TAGS, patrz niżej),
 4. bus:on_route   — tag dla way'ów z relacji OSM route=bus (EV bus$on_route),
oraz:
 5. RELATION_SKIP  — pominięcie całych relacji (turn-restrictions),
 6. SYNTHETIC_WAY  — wstrzyknięcie way'ów spoza OSM (kanały nawrotek); wstawiane
    przed pierwszą relacją (zachowuje porządek typów node→way→relation).

ŹRÓDŁO DANYCH (priorytet): rejestr interwencji aplikacji web
(GET /api/routing-interventions/graph-export, Bearer CRON_SECRET)
 → snapshot ostatniego udanego eksportu (GRAPH_INTERVENTIONS_SNAPSHOT)
 → bootstrapy w tym pliku (awaryjne minimum; rejestr jest źródłem prawdy).
Po udanym pobraniu z rejestru snapshot jest nadpisywany. Manifest interventionId
(SYNTHETIC_WAYS_MANIFEST) POST-uje refresh-ors.sh na /graph-export/baked po
udanym rollout.

STRIP_ACCESS_TAGS (env, domyślnie "true"): historyczne globalne zdjęcie
access=private/no ze wszystkich way'ów. BusFlagEncoder ma poprawną semantykę
(private/no zabronione, chyba że bus/psv=yes) — Etap 4 planu uproszczenia to
ustawienie "false" + pełny sweep regresyjny; do tego czasu default zachowuje
dotychczasowe zachowanie mapy.

Użycie:
    transform_osm.py <wejście.osm[.pbf]> <wyjście.osm[.pbf]>

Env: TRASKA_APP_URL, CRON_SECRET, SYNTHETIC_WAYS_MANIFEST,
     GRAPH_INTERVENTIONS_SNAPSHOT, STRIP_ACCESS_TAGS.
"""

import json
import os
import sys
import time
import urllib.request

import osmium
from osmium.osm import mutable

# ============================ Bootstrapy (awaryjne) ============================
# Rejestr interwencji jest źródłem prawdy; poniższe minimum chroni graf, gdyby
# API i snapshot były niedostępne naraz (krytyczne blokady/nawrotki nie mogą
# zniknąć — aktywne zamknięcia na nich polegają). Uzasadnienia: pola `notes`
# rekordów w rejestrze (panel "Interwencje").

SYNTHETIC_WAYS_BOOTSTRAP = [
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

TAG_OVERRIDES_BOOTSTRAP = {
    '1453889955': {'oneway:bus': 'no'},                 # 409 / Metro Kondratowicza
    '27569980': {'psv': 'yes'},                         # Z33 ślimak Dw. Centralny
    '307888832': {'psv': 'yes'},
    '30611690': {'psv': 'yes'},
    '116934893': {'oneway': 'yes'},                     # 106 / Grzybowska
}

RELATION_SKIPS_BOOTSTRAP = {
    '1963216',   # zakręt w lewo przy Sejmie (131)
    '9166265',   # Patriotów→Bysławska (229)
    '18888466',  # Plac Powstańców Warszawy prosto (107)
    '7783785',   # Białobrzeska (154)
    '20253836',  # Stawki w lewo (157)
}


def _merge_export(payload):
    """Payload graph-export → znormalizowane struktury + lista interventionId."""
    ways = {}
    for entry in payload.get('syntheticWays', []):
        way_def = entry.get('wayDef') or {}
        if way_def.get('id') and way_def.get('nds') and way_def.get('tags'):
            ways[way_def['id']] = way_def
    block_way_ids = set()
    for entry in payload.get('wayBlocks', []):
        block_way_ids.update(str(i) for i in (entry.get('wayIds') or []))
    tag_overrides = {}
    for entry in payload.get('tagOverrides', []):
        defn = entry.get('tagOverride') or {}
        for way_id in defn.get('wayIds') or []:
            tag_overrides.setdefault(str(way_id), {}).update(defn.get('setTags') or {})
    skip_relations = {str(i) for entry in payload.get('relationSkips', [])
                      for i in (entry.get('relationIds') or [])}
    intervention_ids = [
        entry.get('interventionId')
        for key in ('syntheticWays', 'wayBlocks', 'tagOverrides', 'relationSkips')
        for entry in payload.get(key, [])
    ]
    bus_route_way_ids = ({str(i) for i in payload.get('busRouteWayIds', [])}
                         if payload.get('busRouteWayIdsAvailable') else set())
    return {
        'synthetic_ways': ways,
        'block_way_ids': block_way_ids,
        'tag_overrides': tag_overrides,
        'skip_relations': skip_relations,
        'bus_route_way_ids': bus_route_way_ids,
        'intervention_ids': [i for i in intervention_ids if isinstance(i, int)],
    }


def load_graph_interventions():
    """Rejestr → snapshot → bootstrap (z logiem, którego źródła użyto).

    Bootstrapy są zawsze bazą (dedup — rejestr/snapshot wygrywa), żeby świeże
    środowisko bez rejestru wciąż produkowało bezpieczny graf.
    """
    data = {
        'synthetic_ways': {w['id']: w for w in SYNTHETIC_WAYS_BOOTSTRAP},
        'block_way_ids': set(WAY_BLOCKS_BOOTSTRAP),
        'tag_overrides': {k: dict(v) for k, v in TAG_OVERRIDES_BOOTSTRAP.items()},
        'skip_relations': set(RELATION_SKIPS_BOOTSTRAP),
        'bus_route_way_ids': set(),
        'intervention_ids': [],
    }
    snapshot_path = os.environ.get('GRAPH_INTERVENTIONS_SNAPSHOT')
    app_url = os.environ.get('TRASKA_APP_URL', 'http://localhost:3000')
    secret = os.environ.get('CRON_SECRET')

    payload = None
    source = 'bootstrap'
    if secret:
        try:
            req = urllib.request.Request(
                f'{app_url}/api/routing-interventions/graph-export',
                headers={'Authorization': f'Bearer {secret}'},
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                payload = json.load(resp)
            source = 'rejestr'
            if snapshot_path:
                try:
                    with open(snapshot_path, 'w') as f:
                        json.dump(payload, f)
                    print(f'✓ Snapshot eksportu zapisany: {snapshot_path}')
                except Exception as e:
                    print(f'WARN: nie udało się zapisać snapshotu: {e}')
        except Exception as e:
            print(f'WARN: rejestr interwencji niedostępny ({e})')
    else:
        print('WARN: brak CRON_SECRET w env — rejestr interwencji pominięty')

    if payload is None and snapshot_path and os.path.isfile(snapshot_path):
        try:
            with open(snapshot_path) as f:
                payload = json.load(f)
            source = 'snapshot'
        except Exception as e:
            print(f'WARN: snapshot nieczytelny ({e})')

    if payload is not None:
        merged = _merge_export(payload)
        data['synthetic_ways'].update(merged['synthetic_ways'])
        data['block_way_ids'] |= merged['block_way_ids']
        for way_id, tags in merged['tag_overrides'].items():
            data['tag_overrides'].setdefault(way_id, {}).update(tags)
        data['skip_relations'] |= merged['skip_relations']
        data['bus_route_way_ids'] = merged['bus_route_way_ids']
        data['intervention_ids'] = merged['intervention_ids']

    print(f"✓ Interwencje grafowe ze źródła: {source} — "
          f"{len(data['synthetic_ways'])} synthetic, {len(data['block_way_ids'])} block, "
          f"{len(data['tag_overrides'])} tag-override, {len(data['skip_relations'])} rel-skip, "
          f"{len(data['bus_route_way_ids'])} bus_route_way_ids")

    manifest_path = os.environ.get('SYNTHETIC_WAYS_MANIFEST')
    if manifest_path:
        try:
            with open(manifest_path, 'w') as f:
                json.dump({'ids': data['intervention_ids']}, f)
            print(f'✓ Manifest baked zapisany: {manifest_path}')
        except Exception as e:
            print(f'WARN: nie udało się zapisać manifestu baked: {e}')

    return data


class TransformHandler(osmium.SimpleHandler):
    """Kopiuje obiekty do writera, nakładając transformacje (docstring modułu)."""

    def __init__(self, writer, interventions, strip_access=True):
        super().__init__()
        self.w = writer
        self.iv = interventions
        self.strip_access = strip_access
        self.synthetic_written = False
        # Guard na reprocessing: way'e syntetyczne obecne w wejściu nie są
        # wstrzykiwane ponownie (duplikat ID).
        self.synthetic_in_input = set()
        self.stats = {'ways': 0, 'blocked': 0, 'overridden': 0,
                      'access_stripped': 0, 'on_route': 0, 'relations_skipped': 0}

    def node(self, n):
        self.w.add_node(n)

    def way(self, w):
        self.stats['ways'] += 1
        way_id = str(w.id)
        if way_id in self.iv['synthetic_ways']:
            self.synthetic_in_input.add(way_id)

        tags = [(t.k, t.v) for t in w.tags]
        changed = False

        # 1. WAY_BLOCK: wycięcie z grafu przez highway=construction.
        if way_id in self.iv['block_way_ids']:
            new_tags = [(k, 'construction' if k == 'highway' else v) for k, v in tags]
            if new_tags != tags:
                tags, changed = new_tags, True
                self.stats['blocked'] += 1

        # 2. TAG_OVERRIDE: nadpisz istniejące klucze, dołóż brakujące.
        override = self.iv['tag_overrides'].get(way_id)
        if override:
            present = {k for k, _ in tags}
            tags = [(k, override.get(k, v) if k in override else v) for k, v in tags]
            tags += [(k, v) for k, v in override.items() if k not in present]
            changed = True
            self.stats['overridden'] += 1

        # 3. Strip access=private/no (STRIP_ACCESS_TAGS; Etap 4 = wyłączenie).
        if self.strip_access:
            stripped = [(k, v) for k, v in tags
                        if not (k == 'access' and v in ('private', 'no'))]
            if len(stripped) != len(tags):
                tags, changed = stripped, True
                self.stats['access_stripped'] += 1

        # 4. bus:on_route=yes dla way'ów z relacji route=bus (tylko drogi,
        #    nie way'e zablokowane — construction i tak wypada z grafu).
        if (way_id in self.iv['bus_route_way_ids']
                and way_id not in self.iv['block_way_ids']
                and any(k == 'highway' for k, _ in tags)
                and not any(k == 'bus:on_route' for k, _ in tags)):
            tags.append(('bus:on_route', 'yes'))
            changed = True
            self.stats['on_route'] += 1

        self.w.add_way(w.replace(tags=tags) if changed else w)

    def relation(self, r):
        # Relacje idą po way'ach — ostatni moment na wstrzyknięcie syntetycznych
        # way'ów z zachowaniem porządku typów (node → way → relation).
        if not self.synthetic_written:
            self._write_synthetic_ways()
        if str(r.id) in self.iv['skip_relations']:
            self.stats['relations_skipped'] += 1
            return
        self.w.add_relation(r)

    def _write_synthetic_ways(self):
        for sw in self.iv['synthetic_ways'].values():
            if sw['id'] in self.synthetic_in_input:
                print(f"Skip synthetic way {sw['id']} — już obecny w pliku wejściowym")
                continue
            self.w.add_way(mutable.Way(
                id=int(sw['id']),
                version=1,
                nodes=[int(nd) for nd in sw['nds']],
                tags=list(sw['tags'].items()),
            ))
            print(f"Injected synthetic way {sw['id']} ({sw['tags'].get('name', 'bez nazwy')})")
        self.synthetic_written = True

    def finish(self):
        """Fallback: plik bez relacji — wstrzyknij syntetyczne way'e przed zamknięciem."""
        if not self.synthetic_written:
            self._write_synthetic_ways()


def transform(input_file, output_file, interventions, strip_access=True):
    if not os.path.isfile(input_file):
        print(f"Error: brak pliku wejściowego '{input_file}'")
        return False
    if os.path.isfile(output_file):
        print(f"Output '{output_file}' istnieje — nadpisuję.")
        os.remove(output_file)

    start = time.time()
    writer = osmium.SimpleWriter(output_file)
    handler = TransformHandler(writer, interventions, strip_access=strip_access)
    try:
        handler.apply_file(input_file)
        handler.finish()
    finally:
        writer.close()

    s = handler.stats
    print("\nTransformacja zakończona:")
    print(f"  - ways: {s['ways']:,} (blocked {s['blocked']}, tag-override {s['overridden']}, "
          f"access-strip {s['access_stripped']}, bus:on_route {s['on_route']})")
    print(f"  - relations skipped: {s['relations_skipped']}")
    print(f"  - czas: {time.time() - start:.1f} s")
    print(f"  - wejście:  {os.path.getsize(input_file) / 1024 / 1024:.1f} MB")
    print(f"  - wyjście:  {os.path.getsize(output_file) / 1024 / 1024:.1f} MB")
    return True


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(f"Użycie: {sys.argv[0]} <wejście.osm[.pbf]> <wyjście.osm[.pbf]>")
        sys.exit(1)
    strip = os.environ.get('STRIP_ACCESS_TAGS', 'true').lower() != 'false'
    if not strip:
        print("STRIP_ACCESS_TAGS=false — access=private/no zostają w mapie "
              "(semantykę dostępu egzekwuje BusFlagEncoder)")
    interventions = load_graph_interventions()
    ok = transform(sys.argv[1], sys.argv[2], interventions, strip_access=strip)
    sys.exit(0 if ok else 1)
