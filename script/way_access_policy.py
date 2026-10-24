"""Share the legacy way access transformation across graph builders."""


def should_strip_way_access(tags):
    parking = tags.get('highway') == 'service' and tags.get('service') == 'parking_aisle'
    return not parking and tags.get('access') in ('private', 'no')
