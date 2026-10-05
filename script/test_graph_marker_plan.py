import contextlib
import io
from itertools import product
import transform_osm
import json
from pathlib import Path
import tempfile
import unittest

import osmium

from graph_marker_plan import MarkerPlan, MARKER_TAG

FIXTURES = Path(__file__).parent / 'test-fixtures'


class GraphMarkerPlanTest(unittest.TestCase):
    def plan(self):
        return MarkerPlan(FIXTURES / 'k9-small-marker-map.xml',
                          json.loads((FIXTURES / 'k9-small-marker-snapshot.json').read_text()), False)

    def test_kierunek_i_predkosc_maja_cztery_niezalezne_kombinacje(self):
        plan = self.plan()
        self.assertEqual({tuple(v['active']) for v in plan.way_variants[21]}, {(), (11,), (12,), (11, 12)})
        for variant in plan.way_variants[21]:
            plan.bus_ways = set(plan.snapshot['busRouteWayIds'])
            tags = plan.way_tags(21, variant['active'], plan.ways[21]['tags'])
            self.assertEqual(tags['oneway'], '-1' if 11 in variant['active'] else 'yes')
            self.assertEqual(tags['maxspeed'], '50' if 12 in variant['active'] else '30')
            self.assertEqual(tags['bus:on_route'], 'yes')

    def test_wylaczana_droga_boczna_ma_osobne_skrzyzowanie_i_wariant_glownej(self):
        plan = self.plan()
        off, on = plan.way_variants[10]
        side_off, side_on = plan.way_variants[12]
        self.assertEqual(off['nodes'][1], side_off['nodes'][0])
        self.assertEqual(on['nodes'][1], side_on['nodes'][0])
        self.assertNotEqual(off['nodes'][1], on['nodes'][1])
        self.assertNotEqual(off['nodes'][2], on['nodes'][2])
        self.assertEqual(off['nodes'][0], on['nodes'][0])

    def test_szlaban_zachowuje_oryginalny_tag_dla_off(self):
        plan = self.plan()
        raw = plan.nodes[32]['tags']
        self.assertNotIn('psv', plan.node_tags(32, (), raw))
        self.assertEqual(plan.node_tags(32, (13,), raw)['psv'], 'yes')

    def test_syntetyczna_droga_znika_w_off_a_oficjalne_uprawnienie_zostaje(self):
        plan = self.plan()
        plan.bus_ways = set()
        self.assertEqual(plan.way_tags(41, (), plan.ways[41]['tags'])['highway'], 'construction')
        self.assertEqual(plan.way_tags(41, (14,), plan.ways[41]['tags'])['highway'], 'residential')
        self.assertEqual(plan.way_tags(61, (), {'highway': 'residential', 'access': 'private'})['psv'], 'yes')
        self.assertEqual(plan.policy['officialInterventionIds'], [99])

    def test_only_nie_zamyka_kopii_dozwolonej_drogi(self):
        plan = self.plan()
        rewritten = plan.replacement_relations[60]
        self.assertEqual(len(rewritten), 2)
        allowed_ids = {v['id'] for v in plan.way_variants[51]}
        self.assertTrue(all(r['tags']['restriction'] == 'no_straight_on' for r in rewritten))
        self.assertTrue(all(r['members'][-1][1] not in allowed_ids for r in rewritten))

    def test_wyprodukowana_mapa_ma_unikalne_id_i_kompletne_referencje(self):
        plan = self.plan()
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'markers.xml'
            plan.write(output)
            nodes, ways, relations = {}, {}, {}
            class Read(osmium.SimpleHandler):
                def node(self, node):
                    if node.id in nodes:
                        raise AssertionError('Duplicate node')
                    nodes[node.id] = dict(node.tags)
                def way(self, way):
                    if way.id in ways:
                        raise AssertionError('Duplicate way')
                    ways[way.id] = {'nodes': [n.ref for n in way.nodes], 'tags': dict(way.tags)}
                def relation(self, relation):
                    if relation.id in relations:
                        raise AssertionError('Duplicate relation')
                    relations[relation.id] = [(m.type, m.ref) for m in relation.members]
            Read().apply_file(str(output))
            self.assertTrue(all(set(way['nodes']) <= set(nodes) for way in ways.values()))
            self.assertTrue(all(ref in (nodes if kind == 'n' else ways) for members in relations.values() for kind, ref in members))
            self.assertEqual(sum(MARKER_TAG in way['tags'] for way in ways.values()), sum(map(len, plan.way_variants.values())))
            fixture = FIXTURES / 'k9-small-marker-produced.xml'
            self.assertEqual(output.read_bytes(), fixture.read_bytes())
            self.assertEqual(plan.policy, json.loads((FIXTURES / 'k9-small-marker-policy.json').read_text()))

    def test_wszystkie_mapy_referencyjne_powstaly_starym_transformatorem(self):
        snapshot = self.plan().snapshot
        ids = snapshot['markerChannels']['qualityInterventionIds']
        official = set(snapshot['markerChannels']['officialInterventionIds'])
        baselines = []
        with tempfile.TemporaryDirectory() as folder:
            for flags in product((False, True), repeat=len(ids)):
                active = [identity for identity, enabled in zip(ids, flags) if enabled]
                selected = set(active) | official
                payload = {**snapshot, **{key: [e for e in snapshot[key] if e['interventionId'] in selected]
                    for key in ('wayBlocks', 'tagOverrides', 'syntheticWays', 'relationSkips')}}
                output = Path(folder) / 'ordinary.xml'
                with contextlib.redirect_stdout(io.StringIO()):
                    transform_osm.transform(str(FIXTURES / 'k9-small-marker-map.xml'), str(output), transform_osm._merge_export(payload), False)
                baselines.append({'activeInterventionIds': active, 'xml': output.read_text()})
        frozen = json.loads((FIXTURES / 'k9-small-marker-baselines.json').read_text())
        self.assertEqual(baselines, [{'activeInterventionIds': row['activeInterventionIds'], 'xml': ''.join(frozen['fragments'][i] for i in row['xmlFragmentIds'])} for row in frozen['maps']])

    def test_restrykcja_via_way_pozostaje_bez_zmian_jak_w_importerze(self):
        self.assertNotIn(69, self.plan().replacement_relations)
