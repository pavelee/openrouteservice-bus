"""Validate the graph's frozen token selection contract."""
import re
from pathlib import Path

from graph_patch_snapshot import digest, file_digest

BUNDLE_FILES = ('graph_marker_transform.py', 'graph_marker_plan.py', 'graph_marker_policy.py', 'graph_patch_snapshot.py')


def bundle_digest(directory):
    return digest({name: file_digest(Path(directory) / name) for name in BUNDLE_FILES})


def marker_ids(values):
    if not isinstance(values, list) or any(type(value) is not int or not 0 < value <= 9007199254740991 for value in values) or values != sorted(set(values)):
        raise ValueError('Marker identities must be positive, sorted and unique')
    return values


def validate_policy(policy):
    if not isinstance(policy, dict) or policy.get('schema') != 'route-quality-graph-marker-policy-v1':
        raise ValueError('Invalid graph marker policy')
    body = {key: value for key, value in policy.items() if key != 'policySha256'}
    if policy.get('policySha256') != digest(body):
        raise ValueError('Graph marker policy fingerprint differs')
    if not re.fullmatch('rq-graph-v2:[a-f0-9]{64}', str(policy.get('setVersion', ''))):
        raise ValueError('Invalid marker set version')
    for key in ('snapshotSha256', 'encodingRequestSha256'):
        if not re.fullmatch('[a-f0-9]{64}', str(policy.get(key, ''))):
            raise ValueError('Invalid marker fingerprint')
    for key in ('flagEncoderOptions', 'encoder'):
        if not isinstance(policy.get(key), str) or not policy[key].strip():
            raise ValueError('Missing marker encoder')
    official, quality = map(lambda key: set(marker_ids(policy.get(key))), ('officialInterventionIds', 'qualityInterventionIds'))
    if official & quality or not set(marker_ids(policy.get('rebuildOnlyInterventionIds'))) <= quality:
        raise ValueError('Invalid marker channels')
    variants = policy.get('variants')
    if not isinstance(variants, list):
        raise ValueError('Missing marker variants')
    tokens, groups = set(), {}
    for variant in variants:
        token = variant.get('token')
        if type(token) is not int or not 0 < token <= 2147483647 or token in tokens:
            raise ValueError('Invalid marker token')
        tokens.add(token)
        dependencies = tuple(marker_ids(variant.get('interventionIds')))
        active = tuple(marker_ids(variant.get('activeInterventionIds')))
        if not set(dependencies) <= quality or not set(active) <= set(dependencies) or len(dependencies) > 12:
            raise ValueError('Invalid marker dependencies')
        group = groups.setdefault(dependencies, set())
        if active in group:
            raise ValueError('Duplicate marker state')
        group.add(active)
    if any(len(states) != 2 ** len(dependencies) for dependencies, states in groups.items()):
        raise ValueError('Incomplete marker states')
    return policy
