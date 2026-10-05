import json
from pathlib import Path
import tempfile
import unittest

from graph_marker_transform import transform_markers
from graph_marker_policy import bundle_digest
from graph_patch_snapshot import file_digest, validate_build_inputs

FIXTURES = Path(__file__).parent / 'test-fixtures'


class FrozenEncoder:
    options = 'turn_costs=true'

    def __init__(self, directory):
        self.jar, self.config = directory / 'encoder.jar', directory / 'config.yml'
        self.jar.write_bytes(b'bound-encoder')
        self.config.write_bytes(b'bound-config')
        self.jar_sha256, self.config_sha256 = file_digest(self.jar), file_digest(self.config)

    def __call__(self, request):
        return json.loads((FIXTURES / 'k9-small-marker-encoding.json').read_text())

    def unchanged(self):
        if file_digest(self.jar) != self.jar_sha256 or file_digest(self.config) != self.config_sha256:
            raise ValueError('Encoder inputs changed')


class GraphMarkerTransformTest(unittest.TestCase):
    def build(self, root):
        encoder = FrozenEncoder(root)
        output = root / 'map.osm'
        snapshot = FIXTURES / 'k9-small-marker-snapshot.json'
        receipt = transform_markers(FIXTURES / 'k9-small-marker-map.xml', output, json.loads(snapshot.read_text()), encoder, False)
        return encoder, output, snapshot, receipt

    def validate(self, encoder, output, snapshot):
        return validate_build_inputs(snapshot, str(output) + '.graph-input.json', output, Path(__file__).parent / 'graph_marker_transform.py', encoder.jar, encoder.config)

    def test_markerowa_transformacja_daje_mape_i_powiazane_pokwitowanie(self):
        with tempfile.TemporaryDirectory() as folder:
            encoder, output, snapshot, receipt = self.build(Path(folder))
            self.assertEqual(receipt, self.validate(encoder, output, snapshot))
            self.assertEqual(output.read_bytes(), (FIXTURES / 'k9-small-marker-produced.xml').read_bytes())
            self.assertEqual(receipt['transformBundleSha256'], bundle_digest(Path(__file__).parent))

    def test_budowa_nie_nadpisuje_poprzedniego_wyniku(self):
        with tempfile.TemporaryDirectory() as folder:
            encoder, output, snapshot, receipt = self.build(Path(folder))
            before = output.read_bytes()
            with self.assertRaisesRegex(ValueError, 'must be new'):
                transform_markers(FIXTURES / 'k9-small-marker-map.xml', output, json.loads(snapshot.read_text()), encoder, False)
            self.assertEqual(before, output.read_bytes())

    def test_zmieniony_jar_konfiguracja_mapa_lub_polityka_sa_odrzucane(self):
        with tempfile.TemporaryDirectory() as folder:
            encoder, output, snapshot, receipt = self.build(Path(folder))
            for file in (encoder.jar, encoder.config, output, Path(str(output) + '.marker-policy.json')):
                original = file.read_bytes()
                try:
                    file.write_bytes(original + b' ')
                    with self.assertRaises(ValueError):
                        self.validate(encoder, output, snapshot)
                finally:
                    file.write_bytes(original)

    def test_zmiana_pomocnika_po_transformacji_uniewaznia_caly_pakiet(self):
        from graph_marker_policy import BUNDLE_FILES
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            encoder, output, snapshot, receipt = self.build(root)
            bundle = root / 'bundle'
            bundle.mkdir()
            for name in BUNDLE_FILES:
                (bundle / name).write_bytes((Path(__file__).parent / name).read_bytes())
            planner = bundle / 'graph_marker_plan.py'
            planner.write_bytes(planner.read_bytes() + b'\n')
            with self.assertRaisesRegex(ValueError, 'bundle changed'):
                validate_build_inputs(snapshot, str(output) + '.graph-input.json', output, bundle / 'graph_marker_transform.py', encoder.jar, encoder.config)

    def test_markerowe_pokwitowanie_nie_moze_byc_zweryfikowane_bez_jar_i_konfiguracji(self):
        with tempfile.TemporaryDirectory() as folder:
            encoder, output, snapshot, receipt = self.build(Path(folder))
            with self.assertRaisesRegex(ValueError, 'requires the bound'):
                validate_build_inputs(snapshot, str(output) + '.graph-input.json', output, Path(__file__).parent / 'graph_marker_transform.py')


if __name__ == '__main__':
    unittest.main()
