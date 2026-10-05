"""Build local topology variants from an explicit approved marker snapshot."""
import hashlib
import json
from collections import defaultdict, Counter
from itertools import product

import osmium
from osmium.osm import mutable

from graph_patch_snapshot import digest, validate_snapshot

MARKER_TAG = 'bus:quality_variant'
ONLY_EXCLUDE_TAG = 'bus:quality_only_exclude'
MAX_COMBINATIONS = 4096


def states(dependencies):
    ordered = sorted(dependencies)
    if 2 ** len(ordered) > MAX_COMBINATIONS:
        raise ValueError('Graph marker group exceeds the combination budget')
    return [tuple(identity for identity, active in zip(ordered, flags) if active)
            for flags in product((False, True), repeat=len(ordered))]


def compatible(left, right):
    return all((identity in left['active']) == (identity in right['active'])
               for identity in set(left['dependencies']) & set(right['dependencies']))


class MarkerPlan:
    def __init__(self, source, snapshot, strip_access=True, encoding=None, flag_encoder_options="turn_costs=true"):
        validate_snapshot(snapshot)
        if snapshot['schema'] != 'route-quality-graph-snapshot-v2':
            raise ValueError('Explicit marker channels are required')
        self.source, self.snapshot, self.strip_access = str(source), snapshot, strip_access
        if not isinstance(flag_encoder_options, str) or not flag_encoder_options:
            raise ValueError("Explicit graph encoder options are required")
        self.flag_encoder_options = flag_encoder_options
        self.bus_ways = set(snapshot['busRouteWayIds'])
        if any(MARKER_TAG in entry['tagOverride']['setTags'] for entry in snapshot['tagOverrides']) or any(MARKER_TAG in entry['wayDef']['tags'] for entry in snapshot['syntheticWays']):
            raise ValueError('Reserved graph marker tag in an intervention')
        self.official = set(snapshot['markerChannels']['officialInterventionIds'])
        self.way_dependencies, self.node_dependencies = defaultdict(set), defaultdict(set)
        self.ways, self.nodes, self.relations = {}, {}, {}
        self.max_ids = {'node': 0, 'way': 0, 'relation': 0}
        self.synthetic = {}
        self.skip_relations = {int(identity) for entry in snapshot['relationSkips'] for identity in entry['relationIds']}
        for entry in snapshot['wayBlocks']:
            if entry['interventionId'] not in self.official:
                for identity in entry['wayIds']:
                    self.way_dependencies[int(identity)].add(entry['interventionId'])
        for entry in snapshot['tagOverrides']:
            if entry['interventionId'] not in self.official:
                for identity in entry['tagOverride'].get('wayIds', []):
                    self.way_dependencies[int(identity)].add(entry['interventionId'])
                for identity in entry['tagOverride'].get('nodeIds', []):
                    self.node_dependencies[int(identity)].add(entry['interventionId'])
        for entry in snapshot['syntheticWays']:
            way = entry['wayDef']
            self.synthetic[int(way['id'])] = {'id': int(way['id']), 'nodes': [int(n) for n in way['nds']], 'tags': way['tags']}
            if entry['interventionId'] not in self.official:
                self.way_dependencies[int(way['id'])].add(entry['interventionId'])
                for identity in way['nds']:
                    self.node_dependencies[int(identity)].add(entry['interventionId'])
        plan = self

        class Direct(osmium.SimpleHandler):
            def node(self, node):
                plan.max_ids['node'] = max(plan.max_ids['node'], node.id)
                if MARKER_TAG in node.tags:
                    raise ValueError('Reserved graph marker tag in the raw map')

            def way(self, way):
                plan.max_ids['way'] = max(plan.max_ids['way'], way.id)
                if MARKER_TAG in way.tags:
                    raise ValueError('Reserved graph marker tag in the raw map')
                if way.id in plan.synthetic:
                    raise ValueError('Synthetic identifier collides with the raw map')
                if way.id in plan.way_dependencies:
                    for node in way.nodes:
                        plan.node_dependencies[node.ref].update(plan.way_dependencies[way.id])
                    plan.ways[way.id] = plan.copy_way(way)

            def relation(self, relation):
                if ONLY_EXCLUDE_TAG in relation.tags:
                    raise ValueError('Reserved graph marker relation in the raw map')
                plan.max_ids['relation'] = max(plan.max_ids['relation'], relation.id)
                if relation.tags.get('type') == 'restriction' and relation.id not in plan.skip_relations:
                    plan.relations[relation.id] = {'id': relation.id, 'members': [(m.type, m.ref, m.role) for m in relation.members], 'tags': dict(relation.tags)}

        Direct().apply_file(self.source)
        missing = set(self.way_dependencies) - set(self.ways) - set(self.synthetic)
        if missing:
            raise ValueError('Missing quality graph ways: ' + ','.join(map(str, sorted(missing))))

        class Neighbors(osmium.SimpleHandler):
            def way(self, way):
                dependencies = set(plan.way_dependencies.get(way.id, ()))
                for node in way.nodes:
                    dependencies.update(plan.node_dependencies.get(node.ref, ()))
                if dependencies:
                    plan.way_dependencies[way.id] = dependencies
                    plan.ways[way.id] = plan.copy_way(way)

        Neighbors().apply_file(self.source)
        self.ways.update(self.synthetic)
        self.candidates = {node for way in self.ways.values() for node in way['nodes']}
        self.references = Counter()
        self.reference_ways = {}
        self.incident = defaultdict(set)

        class References(osmium.SimpleHandler):
            def node(self, node):
                if node.id in plan.candidates:
                    plan.nodes[node.id] = {'id': node.id, 'location': (node.location.lon, node.location.lat), 'tags': dict(node.tags)}

            def way(self, way):
                relevant = set(node.ref for node in way.nodes) & plan.candidates
                if relevant:
                    plan.reference_ways[way.id] = plan.copy_way(way)
                for identity in relevant:
                    plan.incident[identity].add(way.id)
                if any(identity in plan.node_dependencies for identity in relevant):
                    plan.ways.setdefault(way.id, plan.copy_way(way))

        References().apply_file(self.source)
        if self.candidates - set(self.nodes):
            raise ValueError('Missing graph marker nodes')
        self.reference_ways.update(self.synthetic)
        for identity, way in self.synthetic.items():
            for node in way['nodes']:
                self.way_dependencies[identity].update(self.node_dependencies.get(node, ()))
        self.encoding_results = self.encode(encoding)
        for identity, way in self.reference_ways.items():
            if self.encoding_results['reference:' + str(identity)]['accepted']:
                for node in way['nodes']:
                    if node in self.candidates:
                        self.references[node] += 1
        self.max_ids['way'] = max([self.max_ids['way'], *self.synthetic.keys()])
        for way in self.synthetic.values():
            for node in set(way['nodes']):
                self.incident[node].add(way['id'])
        self.raw_node_max = self.max_ids['node']
        self.node_variants, self.way_variants = {}, {}
        for identity, way in self.synthetic.items():
            for node in way['nodes']:
                self.way_dependencies[identity].update(self.node_dependencies.get(node, ()))
        self.tokens = {}
        for identity, dependencies in sorted(self.node_dependencies.items()):
            self.node_variants[identity] = {active: identity if not active else self.allocate('node') for active in states(dependencies)}
        for identity, way in sorted(self.ways.items()):
            dependencies = sorted(self.way_dependencies[identity])
            variants = []
            for active in states(dependencies):
                token_key = (tuple(dependencies), active)
                if token_key not in self.tokens:
                    self.tokens[token_key] = len(self.tokens) + 1
                variant_nodes = []
                for position, node in enumerate(way['nodes']):
                    private_pillar = position not in (0, len(way['nodes']) - 1) and self.node_degree(node, active) <= 1
                    if active and private_pillar:
                        cloned = self.allocate('node')
                        self.nodes[cloned] = {**self.nodes[node], 'id': cloned, 'originalId': node, 'active': active}
                        variant_nodes.append(cloned)
                    elif node in self.node_variants:
                        projected = tuple(i for i in active if i in self.node_dependencies[node])
                        variant_nodes.append(self.node_variants[node][projected])
                    else:
                        variant_nodes.append(node)
                variants.append({'id': identity if not active else self.allocate('way'), 'nodes': variant_nodes,
                                 'dependencies': dependencies, 'active': active, 'token': self.tokens[token_key]})
            self.way_variants[identity] = variants
        self.replacement_relations = self.build_relations()

    def encode(self, encoding):
        if encoding is None:
            raise ValueError('Graph markers require the actual ORS way encoder')
        entries = []
        for identity, way in sorted(self.reference_ways.items()):
            entries.append({'key': 'reference:' + str(identity), 'id': str(identity), 'nodes': list(map(str, way['nodes'])),
                            'tags': self.way_tags(identity, (), way['tags'])})
        for identity, way in sorted(self.ways.items()):
            for active in states(self.way_dependencies[identity]):
                entries.append({'key': self.encoding_key(identity, active), 'id': str(identity), 'nodes': list(map(str, way['nodes'])),
                                'tags': self.way_tags(identity, active, way['tags'])})
        restrictions = sorted({value for relation in self.relations.values() for key, value in relation['tags'].items()
                               if key == 'restriction' or key.startswith('restriction:')})
        request = {'schema': 'route-quality-graph-way-encoding-v1', 'flagEncoderOptions': self.flag_encoder_options, 'ways': entries, 'restrictions': restrictions}
        encoded = json.dumps(request, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
        response = encoding(encoded)
        if response.get('schema') != 'route-quality-graph-way-encoding-result-v1' or response.get('requestSha256') != hashlib.sha256(encoded).hexdigest():
            raise ValueError('Graph way encoder response differs from its input')
        values = response.get('ways', [])
        results = {}
        for value in values:
            key = value.get('key')
            if key in results or any(type(value.get(field)) is not bool for field in ('accepted', 'forward', 'backward')):
                raise ValueError('Invalid graph way encoding result')
            if not value['accepted'] and (value['forward'] or value['backward']):
                raise ValueError('Rejected graph way cannot have access')
            results[key] = value
        if set(results) != {entry['key'] for entry in entries}:
            raise ValueError('Incomplete graph way encoding result')
        self.restriction_types = response.get('restrictionTypes', {})
        if set(self.restriction_types) != set(restrictions) or any(value not in ('ONLY', 'NOT', 'UNSUPPORTED') for value in self.restriction_types.values()):
            raise ValueError('Incomplete graph restriction encoding result')
        self.encoding_request_sha256 = response['requestSha256']
        self.encoder = response['encoder']
        return results

    @staticmethod
    def encoding_key(identity, active):
        return 'variant:' + str(identity) + ':' + ','.join(map(str, active))

    def node_degree(self, node, active):
        degree = 0
        for identity in self.incident[node]:
            if identity in self.ways:
                local = tuple(i for i in sorted(self.way_dependencies[identity]) if i in active)
                accepted = self.encoding_results[self.encoding_key(identity, local)]['accepted']
            else:
                accepted = self.encoding_results['reference:' + str(identity)]['accepted']
            if accepted:
                degree += self.reference_ways[identity]['nodes'].count(node)
        return degree

    @staticmethod
    def copy_way(way):
        return {'id': way.id, 'nodes': [n.ref for n in way.nodes], 'tags': dict(way.tags)}

    def allocate(self, kind):
        self.max_ids[kind] += 1
        if self.max_ids[kind] > 9223372036854775807:
            raise ValueError('Graph marker identifier exceeds the OSM range')
        return self.max_ids[kind]

    def enabled(self, identity, active):
        return identity in self.official or identity in active

    def node_tags(self, identity, active, original):
        result = dict(original)
        for entry in self.snapshot['tagOverrides']:
            if self.enabled(entry['interventionId'], active) and str(identity) in entry['tagOverride'].get('nodeIds', []):
                result.update(entry['tagOverride']['setTags'])
        return result

    def way_tags(self, identity, active, original):
        result = dict(original)
        if identity in self.synthetic and not any(self.enabled(entry['interventionId'], active)
                for entry in self.snapshot['syntheticWays'] if int(entry['wayDef']['id']) == identity):
            result['highway'] = 'construction'
        blocked = any(self.enabled(entry['interventionId'], active) and str(identity) in entry['wayIds'] for entry in self.snapshot['wayBlocks'])
        if blocked and 'highway' in result:
            result['highway'] = 'construction'
        for entry in self.snapshot['tagOverrides']:
            if self.enabled(entry['interventionId'], active) and str(identity) in entry['tagOverride'].get('wayIds', []):
                result.update(entry['tagOverride']['setTags'])
        if self.strip_access and result.get('access') in ('private', 'no'):
            del result['access']
        if self.snapshot['busRouteWayIdsAvailable'] and str(identity) in self.bus_ways and not blocked and 'highway' in result:
            result.setdefault('bus:on_route', 'yes')
        return result

    def variants(self, identity):
        return self.way_variants.get(identity, [{'id': identity, 'dependencies': [], 'active': ()}])

    def variant_node(self, identity, variant, node):
        if 'nodes' not in variant:
            return node
        position = self.reference_ways[identity]['nodes'].index(node)
        return variant['nodes'][position]

    def build_relations(self):
        replacements = {}
        for identity, relation in sorted(self.relations.items()):
            members = relation['members']
            if not any(kind == 'w' and ref in self.way_variants or kind == 'n' and ref in self.node_variants for kind, ref, _ in members):
                continue
            via = [ref for kind, ref, role in members if role == 'via' and kind == 'n']
            ways = {role: ref for kind, ref, role in members if kind == 'w'}
            # GraphHopper 4 ignores via-way restrictions.
            if not via and any(kind == 'w' and role == 'via' for kind, _, role in members):
                continue
            if len(via) != 1 or set(ways) != {'from', 'to'} or len(members) != 3:
                raise ValueError('Unsupported affected turn restriction: ' + str(identity))
            via = via[0]
            tags = relation['tags']
            keys = [key for key, value in tags.items() if (key == 'restriction' or key.startswith('restriction:'))
                    and self.restriction_types[value] != 'UNSUPPORTED']
            if not keys:
                continue
            common = {key: value for key, value in tags.items() if key not in keys}
            generated = []
            for key in keys:
                only = self.restriction_types[tags[key]] == 'ONLY'
                targets = sorted(self.incident[via] - {ways['to']}) if only else [ways['to']]
                selected_tags = {**common, key: tags[key]}
                if only:
                    selected_tags[ONLY_EXCLUDE_TAG] = 'v1'
                for target in targets:
                    for left, right in product(self.variants(ways['from']), self.variants(target)):
                        if not compatible(left, right):
                            continue
                        node = self.variant_node(ways['from'], left, via)
                        generated.append({'id': self.allocate('relation'), 'members': [('w', left['id'], 'from'), ('n', node, 'via'), ('w', right['id'], 'to')], 'tags': selected_tags})
            replacements[identity] = generated
        return replacements

    @property
    def policy(self):
        body = {'schema': 'route-quality-graph-marker-policy-v1', 'setVersion': self.snapshot['setVersion'],
                'snapshotSha256': self.snapshot['snapshotSha256'],
                'flagEncoderOptions': self.flag_encoder_options, 'encoder': self.encoder,
                'encodingRequestSha256': self.encoding_request_sha256, 'officialInterventionIds': sorted(self.official),
                'qualityInterventionIds': self.snapshot['markerChannels']['qualityInterventionIds'],
                'rebuildOnlyInterventionIds': sorted(entry['interventionId'] for entry in self.snapshot['relationSkips'] if entry['interventionId'] not in self.official),
                'variants': [{'token': token, 'interventionIds': list(dependencies), 'activeInterventionIds': list(active)} for (dependencies, active), token in self.tokens.items()]}
        return {**body, 'policySha256': digest(body)}

    def write(self, output):
        self.bus_ways = set(self.snapshot['busRouteWayIds'])
        plan = self
        writer = osmium.SimpleWriter(str(output))

        class Writer(osmium.SimpleHandler):
            def node(self, node):
                if node.id in plan.node_variants:
                    for active, identity in plan.node_variants[node.id].items():
                        writer.add_node(node.replace(id=identity, tags=plan.node_tags(node.id, active, dict(node.tags))))
                else:
                    writer.add_node(node.replace(tags=plan.node_tags(node.id, (), dict(node.tags))))

            def way(self, way):
                if not self.extra_nodes:
                    for identity, node in sorted(plan.nodes.items()):
                        if identity > self.raw_node_max:
                            writer.add_node(mutable.Node(id=identity, location=node['location'], tags=plan.node_tags(node['originalId'], node.get('active', ()), node['tags'])))
                    self.extra_nodes = True
                self.emit_way(plan.copy_way(way))

            def emit_way(self, way):
                for variant in plan.variants(way['id']):
                    tags = plan.way_tags(way['id'], variant['active'], way['tags'])
                    if 'token' in variant:
                        tags[MARKER_TAG] = str(variant['token'])
                    writer.add_way(mutable.Way(id=variant['id'], nodes=variant.get('nodes', way['nodes']), tags=tags))

            def relation(self, relation):
                self.synthetics()
                if relation.id in plan.skip_relations:
                    return
                if relation.id in plan.replacement_relations:
                    for replacement in plan.replacement_relations[relation.id]:
                        writer.add_relation(mutable.Relation(**replacement))
                else:
                    writer.add_relation(relation)

            def synthetics(self):
                if not self.synthetic_written:
                    for way in plan.synthetic.values():
                        self.emit_way(way)
                    self.synthetic_written = True

        handler = Writer()
        handler.extra_nodes, handler.synthetic_written = False, False
        handler.raw_node_max = self.raw_node_max
        try:
            handler.apply_file(self.source)
            handler.synthetics()
        finally:
            writer.close()
