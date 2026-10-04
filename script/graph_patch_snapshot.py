"""Validate the versioned graph input without loading the map engine."""
import hashlib
import json
import re


def canonical(value):
    if isinstance(value, dict):
        def order(key):
            if re.fullmatch('0|[1-9][0-9]*', key) and int(key) < 4294967295:
                return (0, int(key))
            return (1, key.encode('utf-16be'))
        return {key: canonical(value[key]) for key in sorted(value, key=order)}
    if isinstance(value, list):
        return [canonical(item) for item in value]
    return value


def digest(value):
    return hashlib.sha256(json.dumps(canonical(value), ensure_ascii=False, separators=(',', ':')).encode(errors='backslashreplace')).hexdigest()


def identifiers(values, allow_empty=False):
    if not isinstance(values, list) or (not values and not allow_empty) or any(
            not isinstance(value, str) or not re.fullmatch('[1-9][0-9]*', value) for value in values):
        raise ValueError('Invalid OSM identifier')


def tags(value):
    if not isinstance(value, dict) or not value or any(not key or not isinstance(item, str) for key, item in value.items()):
        raise ValueError('Invalid graph tags')


def normalized_ids(values):
    return sorted(set(values), key=int)


def validate_snapshot(payload):
    if not isinstance(payload, dict) or payload.get('schema') != 'route-quality-graph-snapshot-v1':
        raise ValueError('A versioned graph snapshot is required')
    body = {key: value for key, value in payload.items() if key != 'snapshotSha256'}
    if payload.get('snapshotSha256') != digest(body):
        raise ValueError('Graph snapshot content differs from its fingerprint')
    fingerprints = []
    seen = set()
    override_values = {}
    synthetic_values = {}
    for collection, kind in [('syntheticWays', 'SYNTHETIC_WAY'), ('wayBlocks', 'WAY_BLOCK'),
                             ('tagOverrides', 'TAG_OVERRIDE'), ('relationSkips', 'RELATION_SKIP')]:
        entries = payload.get(collection)
        if not isinstance(entries, list):
            raise ValueError('Invalid graph collection')
        for entry in entries:
            identity = entry.get('interventionId')
            if type(identity) is not int or not 0 < identity <= 9007199254740991 or identity in seen:
                raise ValueError('Invalid graph intervention identity')
            seen.add(identity)
            if entry.get('graphState') not in ('PENDING', 'BAKED'):
                raise ValueError('Invalid graph state')
            if kind == 'SYNTHETIC_WAY':
                definition = entry['wayDef']
                identifiers([definition['id'], *definition['nds']])
                if len(definition['nds']) < 2:
                    raise ValueError('Invalid synthetic way')
                tags(definition['tags'])
                if definition['id'] in synthetic_values and synthetic_values[definition['id']] != digest(definition):
                    raise ValueError('Conflicting synthetic way')
                synthetic_values[definition['id']] = digest(definition)
            elif kind in ('WAY_BLOCK', 'RELATION_SKIP'):
                values = entry['wayIds' if kind == 'WAY_BLOCK' else 'relationIds']
                identifiers(values)
                definition = normalized_ids(values)
            else:
                override = entry['tagOverride']
                ways, nodes = override.get('wayIds', []), override.get('nodeIds', [])
                identifiers(ways, True)
                identifiers(nodes, True)
                identifiers(ways + nodes)
                tags(override['setTags'])
                for object_type, values in [('way', ways), ('node', nodes)]:
                    for object_id in values:
                        for key, value in override['setTags'].items():
                            target = (object_type, object_id, key)
                            if target in override_values and override_values[target] != value:
                                raise ValueError('Conflicting graph tag')
                            override_values[target] = value
                definition = {'wayIds': normalized_ids(ways), 'nodeIds': normalized_ids(nodes), 'setTags': override['setTags']}
            fingerprints.append({'interventionId': identity, 'kind': kind, 'fingerprint': digest({'kind': kind, 'definition': definition})})
    fingerprints.sort(key=lambda entry: entry['interventionId'])
    if payload.get('entryFingerprints') != fingerprints or payload.get('setVersion') != 'rq-graph-v1:' + digest(fingerprints):
        raise ValueError('Graph set differs from its version')
    identifiers(payload.get('busRouteWayIds'), True)
    if type(payload.get('busRouteWayIdsAvailable')) is not bool:
        raise ValueError('Invalid OSM bus route availability')
    return payload


def read_snapshot(path):
    with open(path) as source:
        try:
            return validate_snapshot(json.load(source))
        except (KeyError, TypeError, AttributeError) as error:
            raise ValueError('Invalid graph snapshot definition') from error


def file_digest(path):
    result = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def validate_receipt(receipt):
    if receipt.get('schema') != 'route-quality-graph-transform-v1' or not re.fullmatch(
            'rq-graph-v1:[a-f0-9]{64}', str(receipt.get('setVersion', ''))):
        raise ValueError('A graph transformation receipt is required')
    for key in ('snapshotSha256', 'rawPbfSha256', 'pbfSha256', 'transformSha256'):
        if not re.fullmatch('[a-f0-9]{64}', str(receipt.get(key, ''))):
            raise ValueError('Invalid graph transformation fingerprint')
    if type(receipt.get('stripAccessTags')) is not bool:
        raise ValueError('Invalid graph access transformation policy')
    return receipt


def validate_build_inputs(snapshot_path, receipt_path, pbf_path, transform_path):
    snapshot = read_snapshot(snapshot_path)
    with open(receipt_path) as source:
        receipt = validate_receipt(json.load(source))
    if any(receipt[key] != snapshot[key] for key in ('setVersion', 'snapshotSha256')):
        raise ValueError('Graph input receipt belongs to another snapshot')
    if receipt['pbfSha256'] != file_digest(pbf_path) or receipt['transformSha256'] != file_digest(transform_path):
        raise ValueError('Graph map or transformation changed after receipt')
    return receipt
