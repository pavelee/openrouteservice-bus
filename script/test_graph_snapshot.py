import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from graph_patch_snapshot import validate_snapshot, digest, classic_snapshot
import transform_osm

FIXTURE = Path(__file__).parent / 'test-fixtures/k9-graph-snapshot.json'


class GraphSnapshotTest(unittest.TestCase):
    def test_klasyczna_kontrola_off_zachowuje_oficjalne_wpisy_i_dlug_restrykcji(self):
        marker=self.marker_snapshot()
        for mode in ('ON','OFF'):
            result=classic_snapshot(marker,mode)
            self.assertEqual(validate_snapshot(result),result)
            self.assertEqual(result['busRouteWayIds'],marker['busRouteWayIds'])
            self.assertEqual(result['relationSkips'],marker['relationSkips'])
            for name in ('wayBlocks','tagOverrides','syntheticWays'):
                expected=marker[name] if mode=='ON' else [entry for entry in marker[name] if entry['interventionId'] in marker['markerChannels']['officialInterventionIds']]
                self.assertEqual(result[name],expected)
        self.assertEqual(marker['schema'],'route-quality-graph-snapshot-v2')

    def test_projekcja_kontroli_nie_przyjmuje_klasycznej_migawki_lub_nieznanego_trybu(self):
        with self.assertRaises(ValueError):classic_snapshot(self.marker_snapshot(),'SHADOW')
        with self.assertRaises(ValueError):classic_snapshot(classic_snapshot(self.marker_snapshot(),'ON'),'OFF')

    def marker_snapshot(self):
        return json.loads((FIXTURE.parent / 'k9-marker-snapshot.json').read_text())

    def test_migawka_markerow_z_typescript_ma_jawne_rozlaczne_kanaly(self):
        snapshot = self.marker_snapshot()
        self.assertEqual(validate_snapshot(snapshot), snapshot)
        self.assertEqual(snapshot['markerChannels']['officialInterventionIds'], [3])
        self.assertEqual(len(snapshot['markerChannels']['qualityInterventionIds']), 24)

    def test_sama_zmiana_kanalu_wymaga_nowej_wersji_zestawu(self):
        snapshot = self.marker_snapshot()
        snapshot['markerChannels']['qualityInterventionIds'].append(3)
        snapshot['markerChannels']['qualityInterventionIds'].sort()
        snapshot['markerChannels']['officialInterventionIds'] = []
        del snapshot['snapshotSha256']
        snapshot['snapshotSha256'] = digest(snapshot)
        with self.assertRaisesRegex(ValueError, 'set differs'):
            validate_snapshot(snapshot)

    def test_lata_nie_moze_zniknac_z_obu_kanalow(self):
        snapshot = self.marker_snapshot()
        snapshot['markerChannels']['officialInterventionIds'] = []
        del snapshot['snapshotSha256']
        snapshot['snapshotSha256'] = digest(snapshot)
        with self.assertRaisesRegex(ValueError, 'partition'):
            validate_snapshot(snapshot)

    def test_lata_nie_moze_nalezec_do_dwoch_kanalow(self):
        snapshot = self.marker_snapshot()
        snapshot['markerChannels']['qualityInterventionIds'].append(3)
        snapshot['markerChannels']['qualityInterventionIds'].sort()
        del snapshot['snapshotSha256']
        snapshot['snapshotSha256'] = digest(snapshot)
        with self.assertRaisesRegex(ValueError, 'partition'):
            validate_snapshot(snapshot)

    def test_klasyczny_transformator_nie_udaje_markerow_dla_v2(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / 'snapshot.json'
            file.write_text(json.dumps(self.marker_snapshot()))
            with patch.dict(os.environ, {'CRON_SECRET': '', 'GRAPH_INTERVENTIONS_SNAPSHOT': str(file)}):
                with self.assertRaisesRegex(ValueError, 'requires marker'):
                    transform_osm.load_graph_interventions()

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

    def test_classic_receipt_detects_a_changed_helper(self):
        from graph_patch_snapshot import validate_build_inputs, file_digest, legacy_bundle_digest, LEGACY_BUNDLE_FILES
        snapshot = json.loads(FIXTURE.read_text())
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name in LEGACY_BUNDLE_FILES:
                (root / name).write_bytes((Path(__file__).parent / name).read_bytes())
            map_file, source, receipt_file = [root / name for name in ('map.pbf', 'snapshot.json', 'receipt.json')]
            transform = root / 'transform_osm.py'
            map_file.write_bytes(b'map')
            source.write_text(json.dumps(snapshot))
            receipt = {'schema': 'route-quality-graph-transform-v1', 'setVersion': snapshot['setVersion'],
                       'snapshotSha256': snapshot['snapshotSha256'], 'rawPbfSha256': 'a' * 64,
                       'pbfSha256': file_digest(map_file), 'transformSha256': file_digest(transform),
                       'transformBundleSha256': legacy_bundle_digest(root), 'stripAccessTags': True}
            receipt_file.write_text(json.dumps(receipt))
            self.assertEqual(validate_build_inputs(source, receipt_file, map_file, transform), receipt)
            helper = root / 'terminal_parking_access.py'
            helper.write_bytes(helper.read_bytes() + b'\n')
            with self.assertRaisesRegex(ValueError, 'bundle changed'):
                validate_build_inputs(source, receipt_file, map_file, transform)

    def test_blad_zapisu_nowego_eksportu_nie_powoduje_powrotu_do_starych_definicji(self):
        snapshot = json.loads(FIXTURE.read_text())
        with patch.dict(os.environ, {'CRON_SECRET': 'test', 'GRAPH_INTERVENTIONS_SNAPSHOT': '/missing-directory/snapshot.json'}), patch('transform_osm.urllib.request.urlopen') as response:
            response.return_value.__enter__.return_value.read.return_value = json.dumps(snapshot).encode()
            with self.assertRaises(OSError):
                transform_osm.load_graph_interventions()
