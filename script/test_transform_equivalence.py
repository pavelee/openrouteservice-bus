#!/usr/bin/env python3
"""
Test równoważności: stary pipeline (fix_private_roads.py, XML→XML z ręcznym
serializerem) vs nowy (transform_osm.py, PyOsmium). Na syntetycznej mapie
pokrywającej wszystkie transformacje porównuje wynik SEMANTYCZNIE
(tagi/refs/members per obiekt, niezależnie od formatu i formatowania).

Uruchomienie (venv ze skryptów):
    script/env/bin/python3 script/test_transform_equivalence.py

Znana, ZAMIERZONA różnica semantyczna (nie testowana, udokumentowana):
stary skrypt NIE dodawał bus:on_route way'om objętym hardcodowanym fixem tagów
(oba branche triggerowały na k=highway, fix wygrywał returnem); nowy dodaje —
czystsza semantyka (transformacje niezależne).
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import osmium  # noqa: E402
import fix_private_roads  # noqa: E402
import transform_osm  # noqa: E402

FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="fixture">
  <node id="1" lat="52.2000" lon="21.0000" version="1"/>
  <node id="2" lat="52.2010" lon="21.0010" version="1"/>
  <node id="3" lat="52.2020" lon="21.0020" version="1"/>
  <node id="4" lat="52.2030" lon="21.0030" version="1"/>
  <node id="2309309019" lat="52.2310" lon="21.0210" version="1"/>
  <node id="10615716693" lat="52.2311" lon="21.0211" version="1"/>
  <node id="5" lat="52.2040" lon="21.0040" version="1">
    <tag k="barrier" v="lift_gate"/>
  </node>
  <way id="100" version="1">
    <nd ref="1"/><nd ref="2"/>
    <tag k="highway" v="residential"/>
    <tag k="access" v="private"/>
    <tag k="name" v="Prywatna"/>
  </way>
  <way id="101" version="1">
    <nd ref="2"/><nd ref="3"/>
    <tag k="highway" v="service"/>
    <tag k="access" v="no"/>
  </way>
  <way id="20930779" version="1">
    <nd ref="1"/><nd ref="3"/>
    <tag k="highway" v="tertiary"/>
    <tag k="name" v="Wi&#347;lana"/>
  </way>
  <way id="1453889955" version="1">
    <nd ref="3"/><nd ref="4"/>
    <tag k="highway" v="tertiary"/>
    <tag k="oneway" v="yes"/>
  </way>
  <way id="27569980" version="1">
    <nd ref="1"/><nd ref="4"/>
    <tag k="highway" v="service"/>
  </way>
  <way id="116934893" version="1">
    <nd ref="2"/><nd ref="4"/>
    <tag k="highway" v="residential"/>
    <tag k="oneway" v="-1"/>
  </way>
  <way id="200" version="1">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/>
    <tag k="highway" v="residential"/>
    <tag k="name" v="NaTrasieBusa"/>
  </way>
  <way id="201" version="1">
    <nd ref="3"/><nd ref="4"/>
    <tag k="natural" v="water"/>
  </way>
  <relation id="1963216" version="1">
    <member type="way" ref="100" role="from"/>
    <member type="node" ref="2" role="via"/>
    <member type="way" ref="101" role="to"/>
    <tag k="type" v="restriction"/>
    <tag k="restriction" v="no_left_turn"/>
  </relation>
  <relation id="300" version="1">
    <member type="way" ref="200" role="from"/>
    <member type="node" ref="3" role="via"/>
    <member type="way" ref="201" role="to"/>
    <tag k="type" v="restriction"/>
    <tag k="restriction" v="no_u_turn"/>
  </relation>
</osm>
"""

BUS_ROUTE_WAY_IDS = {'200', '201'}


class Collector(osmium.SimpleHandler):
    """Semantyczny zrzut pliku OSM: obiekt → (tagi, refs/members)."""

    def __init__(self):
        super().__init__()
        self.nodes = {}
        self.ways = {}
        self.relations = {}

    def node(self, n):
        self.nodes[n.id] = dict((t.k, t.v) for t in n.tags)

    def way(self, w):
        self.ways[w.id] = {
            'tags': dict((t.k, t.v) for t in w.tags),
            'nds': [nd.ref for nd in w.nodes],
        }

    def relation(self, r):
        self.relations[r.id] = {
            'tags': dict((t.k, t.v) for t in r.tags),
            'members': [(m.type, m.ref, m.role) for m in r.members],
        }


def collect(path):
    c = Collector()
    c.apply_file(path)
    return c


def main():
    tmp = tempfile.mkdtemp(prefix='transform-eq-')
    src = os.path.join(tmp, 'fixture.osm')
    out_old = os.path.join(tmp, 'old.osm')
    out_new = os.path.join(tmp, 'new.osm')
    with open(src, 'w') as f:
        f.write(FIXTURE)

    # Stary pipeline: bez API (jawne argumenty = bootstrap + nasze bus_route_way_ids).
    ok = fix_private_roads.process_osm_file(
        src, out_old,
        skip_relations=[],
        bus_route_way_ids=set(BUS_ROUTE_WAY_IDS),
        synthetic_ways=list(fix_private_roads.SYNTHETIC_WAYS),
        block_way_ids=set(fix_private_roads.WAY_BLOCKS_BOOTSTRAP),
    )
    assert ok, 'stary pipeline zwrócił błąd'

    # Nowy pipeline: te same dane przez strukturę interwencji (bootstrapy modułu).
    interventions = {
        'synthetic_ways': {w['id']: w for w in transform_osm.SYNTHETIC_WAYS_BOOTSTRAP},
        'block_way_ids': set(transform_osm.WAY_BLOCKS_BOOTSTRAP),
        'tag_overrides': {k: dict(v) for k, v in transform_osm.TAG_OVERRIDES_BOOTSTRAP.items()},
        'skip_relations': set(transform_osm.RELATION_SKIPS_BOOTSTRAP),
        'bus_route_way_ids': set(BUS_ROUTE_WAY_IDS),
        'intervention_ids': [],
    }
    ok = transform_osm.transform(src, out_new, interventions, strip_access=True)
    assert ok, 'nowy pipeline zwrócił błąd'

    old, new = collect(out_old), collect(out_new)

    errors = []

    def check(cond, msg):
        if not cond:
            errors.append(msg)

    # Zbiory obiektów identyczne.
    check(set(old.nodes) == set(new.nodes),
          f'nody: {set(old.nodes) ^ set(new.nodes)}')
    check(set(old.ways) == set(new.ways),
          f'way\'e: {set(old.ways) ^ set(new.ways)}')
    check(set(old.relations) == set(new.relations),
          f'relacje: {set(old.relations) ^ set(new.relations)}')

    # Tagi i refs per obiekt identyczne.
    for nid in sorted(set(old.nodes) & set(new.nodes)):
        check(old.nodes[nid] == new.nodes[nid],
              f'node {nid}: {old.nodes[nid]} != {new.nodes[nid]}')
    for wid in sorted(set(old.ways) & set(new.ways)):
        check(old.ways[wid] == new.ways[wid],
              f'way {wid}: {old.ways[wid]} != {new.ways[wid]}')
    for rid in sorted(set(old.relations) & set(new.relations)):
        check(old.relations[rid] == new.relations[rid],
              f'relacja {rid}: {old.relations[rid]} != {new.relations[rid]}')

    # Asercje zachowań (na NOWYM wyniku — kotwiczą semantykę niezależnie od starego):
    w = new.ways
    check('access' not in w[100]['tags'] and w[100]['tags'].get('name') == 'Prywatna',
          'strip access=private zostawia resztę tagów')
    check('access' not in w[101]['tags'], 'strip access=no')
    check(w[20930779]['tags'].get('highway') == 'construction', 'WAY_BLOCK highway=construction')
    check(w[1453889955]['tags'].get('oneway:bus') == 'no', 'TAG_OVERRIDE dokłada oneway:bus=no')
    check(w[27569980]['tags'].get('psv') == 'yes', 'TAG_OVERRIDE dokłada psv=yes')
    check(w[116934893]['tags'].get('oneway') == 'yes', 'TAG_OVERRIDE nadpisuje oneway=-1 na yes')
    check(w[200]['tags'].get('bus:on_route') == 'yes', 'bus:on_route dla way z relacji route=bus')
    check('bus:on_route' not in w[201]['tags'], 'bus:on_route tylko dla dróg (highway)')
    check(9990000001 in w and w[9990000001]['nds'] == [2309309019, 10615716693],
          'synthetic way wstrzyknięty z poprawnymi nd')
    check(1963216 not in new.relations, 'RELATION_SKIP usuwa relację')
    check(300 in new.relations and len(new.relations[300]['members']) == 3,
          'pozostałe relacje nietknięte')
    check(new.nodes.get(5, {}).get('barrier') == 'lift_gate', 'tagi nodów nietknięte')

    if errors:
        print('✗ RÓŻNICE / BŁĘDY:')
        for e in errors:
            print('  -', e)
        sys.exit(1)
    print(f'✓ RÓWNOWAŻNE — {len(new.ways)} ways, {len(new.relations)} relations, '
          f'{len(new.nodes)} nodes (artefakty: {tmp})')


if __name__ == '__main__':
    main()
