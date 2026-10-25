import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import osmium

from graph_marker_plan import MarkerPlan
from graph_patch_snapshot import classic_snapshot, digest, validate_snapshot
import transform_osm

FIXTURE = Path(__file__).parent / 'test-fixtures/synthetic-nodes-snapshot.json'
RAW = '''<osm version="0.6">
<node id="1" version="1" lat="52.2391" lon="20.8996"/>
<node id="2" version="1" lat="52.2390" lon="20.8996"/>
<node id="3" version="1" lat="52.2389" lon="20.8996"/>
<way id="10" version="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
</osm>'''


def snapshot():
    return json.loads(FIXTURE.read_text())


def reseal(payload):
    payload['entryFingerprints'] = [{'interventionId': entry['interventionId'], 'kind': 'SYNTHETIC_WAY',
        'fingerprint': digest({'kind': 'SYNTHETIC_WAY', 'definition': entry['wayDef']})}
        for entry in payload['syntheticWays']]
    payload['setVersion'] = 'rq-graph-v2:' + digest({'entryFingerprints': payload['entryFingerprints'],
                                                 'markerChannels': payload['markerChannels']})
    del payload['snapshotSha256']
    payload['snapshotSha256'] = digest(payload)
    return payload


def encode(request):
    body = json.loads(request)
    ways = []
    for way in body['ways']:
        accepted = way['tags'].get('highway') != 'construction'
        ways.append({'key': way['key'], 'accepted': accepted, 'forward': accepted,
                     'backward': accepted and way['tags'].get('oneway') != 'yes'})
    return {'schema': 'route-quality-graph-way-encoding-result-v1',
            'requestSha256': hashlib.sha256(request).hexdigest(), 'ways': ways,
            'encoder': 'test-bus', 'restrictionTypes': {}}


def read_map(path):
    nodes, ways, order = {}, {}, []
    class Read(osmium.SimpleHandler):
        def node(self, node):
            if node.id in nodes:
                raise AssertionError('Duplicate node')
            nodes[node.id] = (node.location.lon, node.location.lat)
            order.append('node')
        def way(self, way):
            if way.id in ways:
                raise AssertionError('Duplicate way')
            ways[way.id] = {'nodes': [n.ref for n in way.nodes], 'tags': dict(way.tags)}
            order.append('way')
    Read().apply_file(str(path))
    if any(set(way['nodes']) - set(nodes) for way in ways.values()):
        raise AssertionError('Missing node reference')
    if 'node' in order[order.index('way'):]:
        raise AssertionError('Node emitted after ways')
    return nodes, ways


class SyntheticNodesTest(unittest.TestCase):
    def test_typescript_snapshot_is_valid_and_keeps_new_nodes(self):
        source = snapshot()
        self.assertEqual(validate_snapshot(source), source)
        self.assertEqual(classic_snapshot(source, 'ON')['syntheticWays'], source['syntheticWays'])
        self.assertEqual(classic_snapshot(source, 'OFF')['syntheticWays'], [])

    def test_invalid_nodes_are_rejected_before_map_processing(self):
        for kind in ('coordinate', 'duplicate', 'unused', 'anchor'):
            with self.subTest(kind=kind):
                source = snapshot()
                way = source['syntheticWays'][0]['wayDef']
                if kind == 'coordinate':
                    way['nodes'][0]['lat'] = 91
                elif kind == 'duplicate':
                    way['nodes'].append(copy.deepcopy(way['nodes'][0]))
                elif kind == 'unused':
                    way['nds'] = ['2', way['nodes'][0]['id'], '2']
                else:
                    way['nds'] = [way['nodes'][0]['id'], way['nodes'][1]['id'], way['nodes'][0]['id']]
                with self.assertRaisesRegex(ValueError, '[Ss]ynthetic node'):
                    validate_snapshot(reseal(source))

    def test_classic_map_contains_real_connection_and_exact_new_coordinates(self):
        with tempfile.TemporaryDirectory() as folder:
            source, output = Path(folder) / 'raw.osm', Path(folder) / 'on.osm'
            source.write_text(RAW)
            payload = classic_snapshot(snapshot(), 'ON')
            transform_osm.transform(str(source), str(output), transform_osm._merge_export(payload), False)
            nodes, ways = read_map(output)
            self.assertEqual(ways[9999000010]['nodes'], [2, 9999000011, 9999000012, 2])
            self.assertEqual(nodes[9999000011], (20.8995, 52.2389))
            self.assertEqual(nodes[9999000012], (20.8997, 52.2389))
            self.assertIn(2, ways[10]['nodes'])

    def test_marker_map_connects_loop_only_in_its_active_variant(self):
        with tempfile.TemporaryDirectory() as folder:
            source, output = Path(folder) / 'raw.osm', Path(folder) / 'markers.osm'
            source.write_text(RAW)
            plan = MarkerPlan(source, snapshot(), False, encoding=encode)
            plan.write(output)
            nodes, ways = read_map(output)
            main = next(v for v in plan.way_variants[10] if v['active'] == (10010,))
            loop = next(v for v in plan.way_variants[9999000010] if v['active'] == (10010,))
            self.assertEqual(ways[main['id']]['nodes'][1], ways[loop['id']]['nodes'][0])
            self.assertEqual(ways[loop['id']]['nodes'][0], ways[loop['id']]['nodes'][-1])
            self.assertEqual([nodes[n] for n in ways[loop['id']]['nodes'][1:-1]],
                             [(20.8995, 52.2389), (20.8997, 52.2389)])
            self.assertEqual(plan.way_tags(9999000010, (), plan.ways[9999000010]['tags'])['highway'], 'construction')
            self.assertGreater(max(nodes), 9999000012)

    def test_raw_node_collision_stops_both_transforms(self):
        source_snapshot = snapshot()
        way = source_snapshot['syntheticWays'][0]['wayDef']
        way['nds'] = ['2', '1', '9999000012', '2']
        way['nodes'][0]['id'] = '1'
        reseal(source_snapshot)
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'raw.osm'
            source.write_text(RAW)
            with self.assertRaisesRegex(ValueError, 'collides'):
                MarkerPlan(source, source_snapshot, False, encoding=encode)
            with self.assertRaisesRegex(ValueError, 'collides'):
                transform_osm.transform(str(source), str(Path(folder) / 'out.osm'),
                    transform_osm._merge_export(classic_snapshot(source_snapshot, 'ON')), False)

    def test_missing_real_anchor_stops_both_transforms(self):
        source_snapshot = snapshot()
        source_snapshot['syntheticWays'][0]['wayDef']['nds'] = ['15', '9999000011', '9999000012', '15']
        reseal(source_snapshot)
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'raw.osm'
            source.write_text(RAW)
            with self.assertRaisesRegex(ValueError, 'Missing graph marker nodes'):
                MarkerPlan(source, source_snapshot, False, encoding=encode)
            with self.assertRaisesRegex(ValueError, 'Missing synthetic anchor'):
                transform_osm.transform(str(source), str(Path(folder) / 'out.osm'),
                    transform_osm._merge_export(classic_snapshot(source_snapshot, 'ON')), False)


if __name__ == '__main__':
    unittest.main()
