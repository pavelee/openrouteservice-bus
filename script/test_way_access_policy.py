import unittest

from way_access_policy import should_strip_way_access


class WayAccessPolicyTest(unittest.TestCase):
    def test_legacy_access_values(self):
        for value in (None, 'private', 'no', 'yes', 'customers', 'destination'):
            with self.subTest(access=value):
                self.assertEqual(should_strip_way_access({'access': value}),
                                 value in ('private', 'no'))

    def test_other_service_types_keep_legacy_behavior(self):
        for service in (None, 'driveway', 'drive-through'):
            with self.subTest(service=service):
                self.assertTrue(should_strip_way_access({
                    'highway': 'service', 'service': service, 'access': 'private'}))

    def test_parking_aisle_retains_access_restrictions(self):
        for value in ('private', 'no'):
            with self.subTest(access=value):
                self.assertFalse(should_strip_way_access({
                    'highway': 'service', 'service': 'parking_aisle', 'access': value}))


if __name__ == '__main__':
    unittest.main()
