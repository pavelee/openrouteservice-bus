#!/usr/bin/env python3
"""
transform_osm.py , jednoprzebiegowa transformacja mapy OSM dla builda grafu ORS
(profil driving-bus). Zastępuje parę convert_osm_to_xml.py + fix_private_roads.py:
czyta PBF (lub XML) i pisze PBF (lub XML , format po rozszerzeniu pliku), bez
wielogigabajtowego XML pośredniego i bez ręcznie pisanego serializera.

Transformacje (kolejność per way):
 1. WAY_BLOCK      , highway=construction (wycięcie z grafu),
 2. TAG_OVERRIDE   , setTags: nadpisz istniejący tag / dołóż brakujący
    (way'e z `wayIds`, a także węzły z `nodeIds`, np. psv=yes na szlabanie),
 3. strip access   , usunięcie access=private/no (STRIP_ACCESS_TAGS, patrz niżej),
 4. bus:on_route   , tag dla way'ów z relacji OSM route=bus (EV bus$on_route),
oraz:
 5. RELATION_SKIP  , pominięcie całych relacji (turn-restrictions),
 6. SYNTHETIC_WAY  , wstrzyknięcie way'ów spoza OSM (kanały nawrotek); wstawiane
    przed pierwszą relacją (zachowuje porządek typów node→way→relation).

Input: validated route-quality-graph-snapshot-v1 from the registry or a saved
snapshot. Missing or invalid inputs abort the build. No built-in patch sets.
SYNTHETIC_WAYS_MANIFEST carries the intervention ids, set version and snapshot
fingerprint for the existing rollout callback.

STRIP_ACCESS_TAGS (env, domyślnie "true"): historyczne globalne zdjęcie
access=private/no ze wszystkich way'ów. BusFlagEncoder ma poprawną semantykę
(private/no zabronione, chyba że bus/psv=yes) , Etap 4 planu uproszczenia to
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

from graph_patch_snapshot import file_digest, read_snapshot, validate_snapshot

import osmium
from osmium.osm import mutable

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
    node_tag_overrides = {}
    for entry in payload.get('tagOverrides', []):
        defn = entry.get('tagOverride') or {}
        for way_id in defn.get('wayIds') or []:
            tag_overrides.setdefault(str(way_id), {}).update(defn.get('setTags') or {})
        for node_id in defn.get('nodeIds') or []:
            node_tag_overrides.setdefault(str(node_id), {}).update(defn.get('setTags') or {})
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
        'node_tag_overrides': node_tag_overrides,
        'skip_relations': skip_relations,
        'bus_route_way_ids': bus_route_way_ids,
        'intervention_ids': [i for i in intervention_ids if isinstance(i, int)],
    }


def load_graph_interventions():
    """Use one validated producer snapshot; missing inputs abort the build."""
    snapshot_path = os.environ.get('GRAPH_INTERVENTIONS_SNAPSHOT')
    app_url = os.environ.get('TRASKA_APP_URL', 'http://localhost:3000')
    secret = os.environ.get('CRON_SECRET')
    payload = None
    source = 'snapshot'
    if secret:
        for attempt in range(1, 4):
            try:
                req = urllib.request.Request(
                    f'{app_url}/api/routing-interventions/graph-export',
                    headers={'Authorization': f'Bearer {secret}'},
                )
                with urllib.request.urlopen(req, timeout=30) as response:
                    validated = validate_snapshot(json.load(response))
                payload = validated
                source = 'rejestr'
                break
            except Exception as error:
                print(f'WARN: graph registry unavailable or invalid ({attempt}/3: {error})')
                if attempt < 3:
                    time.sleep(10)
    if payload is None:
        if not snapshot_path or not os.path.isfile(snapshot_path):
            raise ValueError('No validated graph registry or snapshot available')
        payload = read_snapshot(snapshot_path)
    if source == 'rejestr' and snapshot_path:
        temporary = snapshot_path + '.tmp'
        with open(temporary, 'w') as target:
            json.dump(payload, target)
        os.replace(temporary, snapshot_path)
    data = _merge_export(payload)
    data['set_version'] = payload['setVersion']
    data['snapshot_sha256'] = payload['snapshotSha256']
    print(f"Graph patches from {source}: {data['set_version']}, "
          f"{len(data['synthetic_ways'])} synthetic, {len(data['block_way_ids'])} block, "
          f"{len(data['tag_overrides'])} tag-override, "
          f"{len(data['node_tag_overrides'])} node-override, {len(data['skip_relations'])} rel-skip, "
          f"{len(data['bus_route_way_ids'])} bus_route_way_ids")
    manifest_path = os.environ.get('SYNTHETIC_WAYS_MANIFEST')
    if manifest_path:
        with open(manifest_path, 'w') as target:
            json.dump({'ids': data['intervention_ids'], 'source': source,
                       'setVersion': payload['setVersion'], 'snapshotSha256': payload['snapshotSha256']}, target)
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
        self.stats = {'ways': 0, 'blocked': 0, 'overridden': 0, 'nodes_overridden': 0,
                      'access_stripped': 0, 'on_route': 0, 'relations_skipped': 0}

    def node(self, n):
        # TAG_OVERRIDE na węźle: ta sama semantyka "set" co dla way'a. Potrzebne dla
        # barier, bo strip access działa tylko na way'ach, a szlaban z access=private
        # enkoder przepuszcza wyłącznie z bus/psv (BusFlagEncoder.handleNodeTags).
        # Przypadek: pętla Os. Górczewska, szlabany 10821146908/10821146909 (2026-09-27).
        override = self.iv['node_tag_overrides'].get(str(n.id))
        if override:
            tags = dict(n.tags)
            tags.update(override)
            self.stats['nodes_overridden'] += 1
            self.w.add_node(n.replace(tags=tags))
            return
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
    print(f"  - nodes: tag-override {s['nodes_overridden']}")
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
    raw_sha = file_digest(sys.argv[1])
    ok = transform(sys.argv[1], sys.argv[2], interventions, strip_access=strip)
    if ok:
        with open(sys.argv[2] + '.graph-input.json', 'w') as target:
            json.dump({'schema': 'route-quality-graph-transform-v1',
                       'setVersion': interventions['set_version'],
                       'snapshotSha256': interventions['snapshot_sha256'],
                       'rawPbfSha256': raw_sha, 'pbfSha256': file_digest(sys.argv[2]),
                       'transformSha256': file_digest(__file__), 'stripAccessTags': strip}, target)
    sys.exit(0 if ok else 1)
