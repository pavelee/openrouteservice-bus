import contextlib
import io
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest

import transform_osm
from test_transform_osm import read_tags
from graph_marker_plan import MarkerPlan
from graph_patch_snapshot import digest
from graph_patch_snapshot import validate_build_inputs

FIXTURE = Path(__file__).parent / 'test-fixtures/terminal-parking-access.xml'


class TerminalParkingTransformTest(unittest.TestCase):
    def assert_access(self, tags):
        for identity in (147698371, 147698373, 1339003166):
            self.assertEqual(tags[identity].get('bus:parking_access'), 'terminal')
        for identity in (248674689, 248674690, 248674691, 248674692, 248674693):
            self.assertNotIn('bus:parking_access', tags[identity])

    def test_only_terminal_parking_receives_compiled_access(self):
        interventions = transform_osm._merge_export({
            'syntheticWays': [], 'wayBlocks': [], 'tagOverrides': [],
            'relationSkips': [], 'busRouteWayIdsAvailable': False})
        with tempfile.TemporaryDirectory() as folder:
            output = str(Path(folder) / 'map.osm')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertTrue(transform_osm.transform(str(FIXTURE), output, interventions))
            tags = read_tags(output)['w']
        self.assert_access(tags)

    def test_marker_transform_uses_the_same_topology_rule(self):
        channels = {'officialInterventionIds': [], 'qualityInterventionIds': []}
        snapshot = {
            'schema': 'route-quality-graph-snapshot-v2', 'entryFingerprints': [],
            'syntheticWays': [], 'wayBlocks': [], 'tagOverrides': [], 'relationSkips': [],
            'busRouteWayIds': [], 'busRouteWayIdsAvailable': False, 'markerChannels': channels,
            'setVersion': 'rq-graph-v2:' + digest({'entryFingerprints': [], 'markerChannels': channels})}
        snapshot['snapshotSha256'] = digest(snapshot)

        def no_quality_variants(encoded):
            self.assertEqual(json.loads(encoded)['ways'], [])
            return {'schema': 'route-quality-graph-way-encoding-result-v1',
                    'requestSha256': hashlib.sha256(encoded).hexdigest(), 'ways': [],
                    'restrictionTypes': {}, 'encoder': 'test-empty-quality-set'}

        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'map.osm'
            MarkerPlan(FIXTURE, snapshot, encoding=no_quality_variants).write(output)
            self.assert_access(read_tags(str(output))['w'])

    def test_graph_overrides_cannot_forge_compiled_access(self):
        with self.assertRaisesRegex(ValueError, 'Reserved parking'):
            transform_osm._merge_export({'tagOverrides': [{'tagOverride': {
                'wayIds': ['1'], 'setTags': {'bus:parking_access': 'terminal'}}}]})

    def test_classic_command_records_its_helper_bundle(self):
        snapshot = {'schema': 'route-quality-graph-snapshot-v1', 'entryFingerprints': [],
                    'syntheticWays': [], 'wayBlocks': [], 'tagOverrides': [], 'relationSkips': [],
                    'busRouteWayIds': [], 'busRouteWayIdsAvailable': False,
                    'setVersion': 'rq-graph-v1:' + digest([])}
        snapshot['snapshotSha256'] = digest(snapshot)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source, output = root / 'snapshot.json', root / 'map.osm'
            source.write_text(json.dumps(snapshot))
            transformer = Path(transform_osm.__file__)
            subprocess.run([sys.executable, str(transformer), str(FIXTURE), str(output)], check=True,
                           capture_output=True, env={**os.environ, 'CRON_SECRET': '',
                               'GRAPH_INTERVENTIONS_SNAPSHOT': str(source), 'STRIP_ACCESS_TAGS': 'true',
                               'SYNTHETIC_WAYS_MANIFEST': str(root / 'manifest.json')})
            receipt = validate_build_inputs(source, str(output) + '.graph-input.json', output, transformer)
            self.assertEqual(len(receipt['transformBundleSha256']), 64)
            self.assert_access(read_tags(str(output))['w'])


if __name__ == '__main__':
    unittest.main()
