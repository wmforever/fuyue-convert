"""Fail closed on omitted, duplicate, unexpected or truncated QA evidence."""


def align_cases(expected_names, records, label):
    names = [record.get('file') for record in records]
    if len(set(expected_names)) != len(expected_names):
        raise ValueError('Duplicate expected case identity')
    if len(names) != len(expected_names) or len(set(names)) != len(names) or set(names) != set(expected_names):
        raise ValueError(label + ': case identity/cardinality mismatch')
    by_name = {record['file']: record for record in records}
    return [by_name[name] for name in expected_names]


def require_native_geometry(truth_cases, before, after):
    names = [case['file'] for case in truth_cases]
    before = align_cases(names, before, 'native before')
    after = align_cases(names, after, 'native after')
    for case, first, last in zip(truth_cases, before, after, strict=True):
        count = case['pages']
        if not isinstance(count, int) or count < 1:
            raise ValueError('Invalid expected page count')
        expected_pages = list(range(1, count + 1))
        for record in [first, last]:
            parsed = record['parsed']
            if parsed['sourcePageCount'] != count or [p.get('pageNumber') for p in parsed['pages']] != expected_pages:
                raise ValueError(case['file'] + ': native page count/identity mismatch')
        for one, two in zip(first['parsed']['pages'], last['parsed']['pages'], strict=True):
            if one['physicalBox'] != two['physicalBox']:
                raise ValueError(case['file'] + ': physical page geometry changed')
            if len(one['textBlocks']) != len(two['textBlocks']):
                raise ValueError(case['file'] + ': native block cardinality changed')
            for a, b in zip(one['textBlocks'], two['textBlocks'], strict=True):
                unchanged_a = {k: v for k, v in a.items() if k != 'style'}
                unchanged_b = {k: v for k, v in b.items() if k != 'style'}
                style_a = {k: v for k, v in a['style'].items() if k not in ['family', 'bold', 'italic']}
                style_b = {k: v for k, v in b['style'].items() if k not in ['family', 'bold', 'italic']}
                if unchanged_a != unchanged_b or style_a != style_b:
                    raise ValueError(case['file'] + ': original native text/geometry/size/color changed')
    return True
