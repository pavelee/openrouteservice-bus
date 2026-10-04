import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from graph_patch_snapshot import validate_snapshot
import transform_osm

FIXTURE = Path(__file__).parent / 'test-fixtures/k9-graph-snapshot.json'


class GraphSnapshotTest(unittest.TestCase):
    def test_migawka_producenta_ma_poprawne_wersje_i_odciski(self):
        snapshot = json.loads(FIXTURE.read_text())
        self.assertEqual(validate_snapshot(snapshot), snapshot)

    def test_zmiana_tresci_bez_nowej_wersji_jest_odrzucana(self):
        snapshot = json.loads(FIXTURE.read_text())
        snapshot['wayBlocks'][0]['wayIds'] = ['123']
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot)

    def test_brak_rejestru_i_migawki_nie_odtwarza_bootstrapu(self):
        with patch.dict(os.environ, {'CRON_SECRET': '', 'GRAPH_INTERVENTIONS_SNAPSHOT': ''}):
            with self.assertRaises(ValueError):
                transform_osm.load_graph_interventions()

    def test_stary_eksport_bez_wersji_nie_jest_poprawna_migawka(self):
        with self.assertRaises(ValueError):
            validate_snapshot({'wayBlocks': []})

    def test_jawny_pusty_zestaw_nie_przywraca_zaszytych_blokad(self):
        from graph_patch_snapshot import digest
        snapshot = json.loads(FIXTURE.read_text())
        for key in ['syntheticWays', 'wayBlocks', 'tagOverrides', 'relationSkips', 'entryFingerprints']:
            snapshot[key] = []
        snapshot['setVersion'] = 'rq-graph-v1:' + digest([])
        del snapshot['snapshotSha256']
        snapshot['snapshotSha256'] = digest(snapshot)
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / 'snapshot.json'
            file.write_text(json.dumps(snapshot))
            with patch.dict(os.environ, {'CRON_SECRET': '', 'GRAPH_INTERVENTIONS_SNAPSHOT': str(file)}):
                result = transform_osm.load_graph_interventions()
        self.assertFalse(result['block_way_ids'])
        self.assertFalse(result['synthetic_ways'])
        self.assertFalse(result['skip_relations'])

    def test_uszkodzony_rejestr_nie_nadpisuje_poprawnej_migawki(self):
        snapshot = json.loads(FIXTURE.read_text())
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / 'snapshot.json'
            file.write_text(json.dumps(snapshot))
            before = file.read_bytes()
            with patch.dict(os.environ, {'CRON_SECRET': 'test', 'GRAPH_INTERVENTIONS_SNAPSHOT': str(file)}), patch('transform_osm.urllib.request.urlopen') as response, patch('transform_osm.time.sleep'):
                response.return_value.__enter__.return_value.read.return_value = b'{"wayBlocks":[]}'
                result = transform_osm.load_graph_interventions()
            self.assertEqual(file.read_bytes(), before)
            self.assertEqual(len(result['block_way_ids']), 14)

    def test_potwierdzenie_transformacji_nie_pasuje_do_innej_mapy(self):
        from graph_patch_snapshot import validate_build_inputs, file_digest
        snapshot = json.loads(FIXTURE.read_text())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            map_file, transform, source, receipt_file = [root / name for name in ['map.pbf', 'transform.py', 'snapshot.json', 'receipt.json']]
            map_file.write_bytes(b'map')
            transform.write_bytes(b'code')
            source.write_text(json.dumps(snapshot))
            receipt = {'schema': 'route-quality-graph-transform-v1', 'setVersion': snapshot['setVersion'],
                       'snapshotSha256': snapshot['snapshotSha256'], 'rawPbfSha256': 'a' * 64,
                       'pbfSha256': file_digest(map_file), 'transformSha256': file_digest(transform), 'stripAccessTags': True}
            receipt_file.write_text(json.dumps(receipt))
            self.assertEqual(validate_build_inputs(source, receipt_file, map_file, transform), receipt)
            map_file.write_bytes(b'other map')
            with self.assertRaisesRegex(ValueError, 'changed'):
                validate_build_inputs(source, receipt_file, map_file, transform)

    def test_blad_zapisu_nowego_eksportu_nie_powoduje_powrotu_do_starych_definicji(self):
        snapshot = json.loads(FIXTURE.read_text())
        with patch.dict(os.environ, {'CRON_SECRET': 'test', 'GRAPH_INTERVENTIONS_SNAPSHOT': '/missing-directory/snapshot.json'}), patch('transform_osm.urllib.request.urlopen') as response:
            response.return_value.__enter__.return_value.read.return_value = json.dumps(snapshot).encode()
            with self.assertRaises(OSError):
                transform_osm.load_graph_interventions()
