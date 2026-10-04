import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from test_transform_osm import read_tags
import transform_osm

FIXTURES = Path(__file__).parent / 'test-fixtures'


class GraphCharacterizationTest(unittest.TestCase):
    def test_zamrozone_laty_daja_identyczny_pbf_w_dwoch_przebiegach(self):
        payload = json.loads((FIXTURES / 'k9-approved-export.json').read_text())
        with tempfile.TemporaryDirectory() as folder:
            definitions = transform_osm._merge_export(payload)
            outputs = []
            for name in ['first', 'second']:
                file = Path(folder) / f'{name}.osm.pbf'
                self.assertTrue(transform_osm.transform(str(FIXTURES / 'k9-raw.osm'), str(file), definitions))
                outputs.append(hashlib.sha256(file.read_bytes()).hexdigest())
            self.assertEqual(outputs[0], outputs[1])
            tags = read_tags(str(file))
            self.assertEqual(tags['w'][20930779]['highway'], 'construction')
            self.assertEqual(tags['w'][1453889955]['oneway:bus'], 'no')
            self.assertEqual(tags['n'][10821146908]['psv'], 'yes')
            self.assertIn(9990000001, tags['w'])


if __name__ == '__main__':
    unittest.main()
