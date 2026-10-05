import copy
import json
from pathlib import Path
import unittest

from graph_patch_snapshot import digest
from graph_marker_policy import validate_policy


class GraphMarkerPolicyTest(unittest.TestCase):
    def policy(self):
        return json.loads((Path(__file__).parent / 'test-fixtures/k9-small-marker-policy.json').read_text())

    def rehash(self, policy):
        policy['policySha256'] = digest({key: value for key, value in policy.items() if key != 'policySha256'})
        return policy

    def test_pelna_zamrozona_polityka_ma_poprawny_skrot(self):
        policy = self.policy()
        self.assertEqual(policy, validate_policy(policy))

    def test_zmiana_opcji_enkodera_bez_nowego_skrotu_jest_odrzucona(self):
        policy = self.policy()
        policy['flagEncoderOptions'] = 'turn_costs=false'
        with self.assertRaisesRegex(ValueError, 'fingerprint'):
            validate_policy(policy)

    def test_brak_lokalnego_stanu_jest_odrzucony_takze_z_nowym_skrotem(self):
        policy = self.policy()
        policy['variants'].pop(0)
        with self.assertRaisesRegex(ValueError, 'Incomplete marker states'):
            validate_policy(self.rehash(policy))

    def test_powtorzony_token_jest_odrzucony(self):
        policy = self.policy()
        policy['variants'][1]['token'] = policy['variants'][0]['token']
        with self.assertRaisesRegex(ValueError, 'token'):
            validate_policy(self.rehash(policy))

    def test_oficjalna_zmiana_nie_moze_byc_zaleznoscia_wylacznika_jakosci(self):
        policy = self.policy()
        variant = policy['variants'][0]
        variant['interventionIds'] = [99]
        with self.assertRaisesRegex(ValueError, 'dependencies'):
            validate_policy(self.rehash(policy))

    def test_identyfikator_nie_moze_byc_flaga_bool(self):
        policy = self.policy()
        policy['officialInterventionIds'] = [True]
        with self.assertRaisesRegex(ValueError, 'identities'):
            validate_policy(self.rehash(policy))

    def test_powtorzony_stan_z_nowym_tokenem_jest_odrzucony(self):
        policy = self.policy()
        duplicate = copy.deepcopy(policy['variants'][0])
        duplicate['token'] = 1000
        policy['variants'].append(duplicate)
        with self.assertRaisesRegex(ValueError, 'Duplicate marker state'):
            validate_policy(self.rehash(policy))


if __name__ == '__main__':
    unittest.main()
