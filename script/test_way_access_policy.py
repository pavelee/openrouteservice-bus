import unittest

from way_access_policy import should_strip_way_access


class WayAccessPolicyTest(unittest.TestCase):
    def test_legacy_access_values(self):
        for value in (None, 'private', 'no', 'yes', 'customers', 'destination'):
            with self.subTest(access=value):
                self.assertEqual(should_strip_way_access({'access': value}),
                                 value in ('private', 'no'))

    def test_legacy_rule_does_not_depend_on_road_type(self):
        for service in (None, 'parking_aisle', 'driveway', 'drive-through'):
            with self.subTest(service=service):
                self.assertTrue(should_strip_way_access({
                    'highway': 'service', 'service': service, 'access': 'private'}))


if __name__ == '__main__':
    unittest.main()
