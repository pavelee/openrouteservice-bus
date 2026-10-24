"""Share the legacy way access transformation across graph builders."""


def should_strip_way_access(tags):
    return tags.get('access') in ('private', 'no')
