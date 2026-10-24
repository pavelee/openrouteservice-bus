"""Compile parking access without introducing a transit shortcut."""
import osmium

PARKING_ACCESS_TAG = 'bus:parking_access'
NON_MOTOR_HIGHWAYS = frozenset(('footway', 'path', 'cycleway', 'steps', 'bridleway', 'platform'))
INTENDED_ACCESS = frozenset(('yes', 'designated', 'permissive', 'destination', 'customers', 'delivery', 'bus', 'psv', 'public_transport'))


def parking_aisle(tags):
    return tags.get('highway') == 'service' and tags.get('service') == 'parking_aisle'


class TerminalParkingAccess:
    def __init__(self, source, possible_road_ids=(), added_ways=()):
        parents, owners = {}, {}
        possible_road_ids = set(map(int, possible_road_ids))

        def root(identity):
            while parents[identity] != identity:
                parents[identity] = parents[parents[identity]]
                identity = parents[identity]
            return identity

        class Parking(osmium.SimpleHandler):
            def way(self, way):
                if PARKING_ACCESS_TAG in way.tags:
                    raise ValueError('Reserved parking access tag in the raw map')
                if not parking_aisle(way.tags):
                    return
                parents[way.id] = way.id
                for node in way.nodes:
                    previous = owners.setdefault(node.ref, way.id)
                    a, b = root(way.id), root(previous)
                    parents[max(a, b)] = min(a, b)

        Parking().apply_file(str(source))
        self.components = {identity: root(identity) for identity in parents}
        self.gateways = {identity: set() for identity in set(self.components.values())}

        def connection(identity, nodes, tags):
            if identity in parents:
                return
            highway = tags.get('highway')
            # Count possible roads, including currently blocked ones, conservatively.
            motor_access = any(tags.get(key) in INTENDED_ACCESS for key in ('bus', 'psv', 'public_transport', 'motor_vehicle', 'vehicle'))
            if identity not in possible_road_ids and (not highway or highway in NON_MOTOR_HIGHWAYS and not motor_access):
                return
            for node in nodes:
                if node in owners:
                    self.gateways[root(owners[node])].add(node)

        if parents:
            class Connections(osmium.SimpleHandler):
                def way(self, way):
                    connection(way.id, (node.ref for node in way.nodes), way.tags)

            Connections().apply_file(str(source))
        for way in added_ways:
            if PARKING_ACCESS_TAG in way['tags']:
                raise ValueError('Reserved parking access tag in a graph patch')
            connection(int(way['id']), map(int, way['nodes']), way['tags'])
        self.way_ids = frozenset(identity for identity, component in self.components.items()
                                 if len(self.gateways[component]) == 1)

    def allows(self, identity, tags):
        return int(identity) in self.way_ids and parking_aisle(tags)
