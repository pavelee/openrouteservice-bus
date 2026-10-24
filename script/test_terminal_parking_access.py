from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from terminal_parking_access import TerminalParkingAccess, PARKING_ACCESS_TAG


class TerminalParkingAccessTest(unittest.TestCase):
    def access(self, roads=(), possible=(), added=(), parking=True):
        document = ET.Element('osm', version='0.6')
        for identity in range(1, 6):
            ET.SubElement(document, 'node', id=str(identity), version='1', lat='52', lon=str(20 + identity / 10000))
        ways = ([(10, [1, 2, 3, 1], {'highway': 'service', 'service': 'parking_aisle'})] if parking else []) + list(roads)
        for identity, nodes, tags in ways:
            way = ET.SubElement(document, 'way', id=str(identity), version='1')
            for node in nodes:
                ET.SubElement(way, 'nd', ref=str(node))
            for key, value in tags.items():
                ET.SubElement(way, 'tag', k=key, v=value)
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'map.osm'
            ET.ElementTree(document).write(source, encoding='utf-8', xml_declaration=True)
            return TerminalParkingAccess(source, possible, added)

    def test_isolated_parking_is_not_a_verified_access(self):
        self.assertEqual(self.access().way_ids, set())

    def test_one_gateway_keeps_an_internal_turnaround(self):
        result = self.access([(20, [1, 4], {'highway': 'residential'})])
        self.assertEqual(result.way_ids, {10})
        self.assertEqual(result.gateways[10], {1})

    def test_two_gateways_cannot_create_a_parking_shortcut(self):
        result = self.access([(20, [1, 4], {'highway': 'residential'}),
                              (30, [2, 5], {'highway': 'service'})])
        self.assertEqual(result.way_ids, set())

    def test_multiple_roads_at_the_same_gateway_are_one_connection(self):
        result = self.access([(20, [1, 4], {'highway': 'residential'}),
                              (30, [1, 5], {'highway': 'service'})])
        self.assertEqual(result.way_ids, {10})

    def test_a_currently_restricted_road_still_counts_conservatively(self):
        result = self.access([(20, [1, 4], {'highway': 'residential'}),
                              (30, [2, 5], {'highway': 'service', 'access': 'private'})])
        self.assertEqual(result.way_ids, set())

    def test_footpaths_are_not_bus_gateways_but_possible_bus_access_is_counted(self):
        road = (20, [1, 4], {'highway': 'residential'})
        footpath = (30, [2, 5], {'highway': 'footway'})
        self.assertEqual(self.access([road, footpath]).way_ids, {10})
        self.assertEqual(self.access([road, footpath], possible=[30]).way_ids, set())
        bus_footpath = (30, [2, 5], {'highway': 'footway', 'bus': 'yes'})
        self.assertEqual(self.access([road, bus_footpath]).way_ids, set())

    def test_existing_graph_additions_can_prevent_a_false_single_gateway(self):
        result = self.access([(20, [1, 4], {'highway': 'residential'})], added=[
            {'id': 30, 'nodes': [2, 5], 'tags': {'highway': 'service'}}])
        self.assertEqual(result.way_ids, set())

    def test_raw_data_and_graph_patches_cannot_declare_compiled_access(self):
        with self.assertRaisesRegex(ValueError, 'Reserved parking'):
            self.access([(20, [1, 4], {'highway': 'residential', PARKING_ACCESS_TAG: 'terminal'})])
        with self.assertRaisesRegex(ValueError, 'Reserved parking'):
            self.access(parking=False, added=[
                {'id': 30, 'nodes': [2, 5], 'tags': {'highway': 'service', PARKING_ACCESS_TAG: 'terminal'}}])


if __name__ == '__main__':
    unittest.main()
