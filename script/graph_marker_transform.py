#!/usr/bin/env python3
"""Transform explicit marker inputs with the encoder that will import them."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

from graph_marker_plan import MarkerPlan
from graph_patch_snapshot import file_digest, read_snapshot
from graph_marker_policy import bundle_digest

MAIN = 'org.heigit.ors.api.routequality.GraphMarkerEncoder'
LAUNCHER = 'org.springframework.boot.loader.launch.PropertiesLauncher'


class Encoder:
    def __init__(self, jar, config, java='java', image=None):
        self.jar, self.config = Path(jar).resolve(strict=True), Path(config).resolve(strict=True)
        self.jar_sha256, self.config_sha256 = file_digest(self.jar), file_digest(self.config)
        if image is not None:
            if not re.fullmatch('sha256:[a-f0-9]{64}', image):
                raise ValueError('Use an immutable encoder image ID')
            actual = subprocess.run(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', image, '/ors.jar'],
                                    check=True, capture_output=True).stdout.decode().split()[0]
            if actual != self.jar_sha256:
                raise ValueError('The encoder image differs from the bound JAR')
            self.command = ['docker', 'run', '--rm', '-i', '--entrypoint', 'java',
                            '-v', str(self.config) + ':/route-quality/config.yml:ro', image,
                            '-Dloader.main=' + MAIN, '-cp', '/ors.jar', LAUNCHER]
            self.config_argument = '/route-quality/config.yml'
        else:
            self.command = [str(java), '-Dloader.main=' + MAIN, '-cp', str(self.jar), LAUNCHER]
            self.config_argument = str(self.config)
        options = self.run('--options')
        if options.get('schema') != 'route-quality-graph-encoder-config-v1' or not isinstance(options.get('flagEncoderOptions'), str):
            raise ValueError('Invalid native encoder configuration')
        self.options = options['flagEncoderOptions']

    def unchanged(self):
        if file_digest(self.jar) != self.jar_sha256 or file_digest(self.config) != self.config_sha256:
            raise ValueError('Encoder inputs changed during transformation')

    def run(self, operation, request=None):
        self.unchanged()
        response = subprocess.run([*self.command, operation, self.config_argument], input=request,
                                  check=True, capture_output=True, timeout=120)
        self.unchanged()
        return json.loads(response.stdout)

    def __call__(self, request):
        return self.run('--encode', request)


def transform_markers(source, output, snapshot, encoder, strip_access=True):
    source, output = Path(source), Path(output)
    policy_file = Path(str(output) + '.marker-policy.json')
    receipt_file = Path(str(output) + '.graph-input.json')
    if any(file.exists() for file in (output, policy_file, receipt_file)):
        raise ValueError('Marker output files must be new')
    raw_sha256 = file_digest(source)
    directory = Path(__file__).parent
    transform_sha256 = file_digest(__file__)
    transform_bundle_sha256 = bundle_digest(directory)
    plan = MarkerPlan(source, snapshot, strip_access=strip_access, encoding=encoder, flag_encoder_options=encoder.options)
    plan.write(output)
    encoder.unchanged()
    if raw_sha256 != file_digest(source) or transform_bundle_sha256 != bundle_digest(directory):
        raise ValueError('Map or transformation changed during marker generation')
    policy = plan.policy
    policy_file.write_text(json.dumps(policy, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n')
    receipt = {'schema': 'route-quality-graph-transform-v2', 'setVersion': snapshot['setVersion'],
               'snapshotSha256': snapshot['snapshotSha256'], 'rawPbfSha256': raw_sha256,
               'pbfSha256': file_digest(output), 'transformSha256': transform_sha256,
               'transformBundleSha256': transform_bundle_sha256, 'stripAccessTags': strip_access,
               'policySha256': policy['policySha256'], 'policyFileSha256': file_digest(policy_file),
               'encoderJarSha256': encoder.jar_sha256, 'configSha256': encoder.config_sha256}
    receipt_file.write_text(json.dumps(receipt, sort_keys=True, separators=(',', ':')) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    parser.add_argument('output')
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--jar', required=True)
    parser.add_argument('--config', required=True)
    parser.add_argument('--encoder-image')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    encoder = Encoder(args.jar, args.config, args.java, args.encoder_image)
    receipt = transform_markers(args.source, args.output, read_snapshot(args.snapshot), encoder,
                                strip_access=os.environ.get('STRIP_ACCESS_TAGS', 'true').lower() != 'false')
    print(json.dumps({'setVersion': receipt['setVersion'], 'policySha256': receipt['policySha256'],
                      'pbfSha256': receipt['pbfSha256']}))


if __name__ == '__main__':
    main()
