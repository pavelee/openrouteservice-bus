#!/usr/bin/env python3
"""Testy transform_osm.py. Uruchomienie: script/env/bin/python -m unittest script/test_transform_osm.py"""

import os
import sys
import tempfile
import unittest

import osmium

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import transform_osm  # noqa: E402

OSM_XML = """<?xml version='1.0' encoding='UTF-8'?>
<osm version="0.6" generator="test">
  <node id="1" version="1" lat="52.2391" lon="20.8996"/>
  <node id="2" version="1" lat="52.2390" lon="20.8996">
    <tag k="barrier" v="gate"/>
    <tag k="access" v="private"/>
  </node>
  <node id="3" version="1" lat="52.2389" lon="20.8996">
    <tag k="barrier" v="gate"/>
    <tag k="access" v="private"/>
  </node>
  <way id="10" version="1">
    <nd ref="1"/>
    <nd ref="2"/>
    <nd ref="3"/>
    <tag k="highway" v="construction"/>
    <tag k="construction" v="service"/>
  </way>
</osm>
"""


def export_payload(tag_overrides):
    return {'syntheticWays': [], 'wayBlocks': [], 'relationSkips': [],
            'busRouteWayIdsAvailable': False,
            'tagOverrides': [{'interventionId': i + 1, 'graphState': 'PENDING', 'tagOverride': t}
                             for i, t in enumerate(tag_overrides)]}


def read_tags(path):
    out = {'n': {}, 'w': {}}

    class H(osmium.SimpleHandler):
        def node(self, n):
            out['n'][n.id] = dict(n.tags)

        def way(self, w):
            out['w'][w.id] = dict(w.tags)

    H().apply_file(path)
    return out


class MergeExportTest(unittest.TestCase):
    def test_node_ids_go_to_node_overrides(self):
        merged = transform_osm._merge_export(export_payload([
            {'wayIds': ['10'], 'setTags': {'highway': 'service'}},
            {'nodeIds': ['2', 3], 'setTags': {'psv': 'yes'}},
        ]))
        self.assertEqual(merged['tag_overrides'], {'10': {'highway': 'service'}})
        self.assertEqual(merged['node_tag_overrides'], {'2': {'psv': 'yes'}, '3': {'psv': 'yes'}})

    def test_way_only_entry_has_no_node_overrides(self):
        merged = transform_osm._merge_export(export_payload([
            {'wayIds': ['10'], 'setTags': {'oneway': 'yes'}},
        ]))
        self.assertEqual(merged['node_tag_overrides'], {})


class TransformTest(unittest.TestCase):
    def run_transform(self, tag_overrides):
        merged = transform_osm._merge_export(export_payload(tag_overrides))
        interventions = {
            'synthetic_ways': {}, 'block_way_ids': set(), 'skip_relations': set(),
            'bus_route_way_ids': set(), 'intervention_ids': [],
            'tag_overrides': merged['tag_overrides'],
            'node_tag_overrides': merged['node_tag_overrides'],
        }
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, 'in.osm')
            dst = os.path.join(tmp, 'out.osm')
            with open(src, 'w') as f:
                f.write(OSM_XML)
            self.assertTrue(transform_osm.transform(src, dst, interventions))
            return read_tags(dst)

    def test_node_override_opens_only_listed_gate(self):
        out = self.run_transform([
            {'wayIds': ['10'], 'setTags': {'highway': 'service'}},
            {'nodeIds': ['2'], 'setTags': {'psv': 'yes'}},
        ])
        self.assertEqual(out['n'][2], {'barrier': 'gate', 'access': 'private', 'psv': 'yes'})
        self.assertEqual(out['n'][3], {'barrier': 'gate', 'access': 'private'})
        self.assertEqual(out['n'][1], {})
        self.assertEqual(out['w'][10]['highway'], 'service')

    def test_node_override_replaces_existing_value(self):
        out = self.run_transform([{'nodeIds': ['3'], 'setTags': {'access': 'yes'}}])
        self.assertEqual(out['n'][3], {'barrier': 'gate', 'access': 'yes'})


if __name__ == '__main__':
    unittest.main()
