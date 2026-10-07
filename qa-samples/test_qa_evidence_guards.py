import copy
import unittest
from qa_evidence_guards import align_cases, require_native_geometry


class EvidenceGuardsTest(unittest.TestCase):
    def setUp(self):
        self.truth = [{'file': 'one.pdf', 'pages': 2}, {'file': 'two.pdf', 'pages': 1}]
        block = {'id': 't1', 'text': 'ID 00731 .28', 'box': [1, 2, 3, 4], 'baselineY': 5,
                 'style': {'family': 'Original', 'bold': False, 'italic': False, 'sizePt': 12, 'color': '000000'}}
        self.native = [{'file': c['file'], 'parsed': {'sourcePageCount': c['pages'], 'pages': [
            {'pageNumber': p, 'physicalBox': [0, 0, 210, 297], 'textBlocks': [copy.deepcopy(block)] if p == 1 else []}
            for p in range(1, c['pages'] + 1)]}} for c in self.truth]

    def test_complete_evidence_allows_only_intended_font_fields(self):
        after = copy.deepcopy(self.native)
        after[0]['parsed']['pages'][0]['textBlocks'][0]['style'].update(family='Corrected', bold=True)
        self.assertTrue(require_native_geometry(self.truth, self.native, after))

    def test_missing_last_native_case_fails(self):
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, self.native[:-1])

    def test_missing_last_native_page_including_blank_page_fails(self):
        after = copy.deepcopy(self.native);after[0]['parsed']['pages'].pop()
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, after)

    def test_both_reports_omitting_same_case_still_fails(self):
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native[:-1], self.native[:-1])

    def test_duplicate_or_unexpected_case_fails(self):
        for records in [[self.native[0], self.native[0]], [{'file': 'other.pdf'}, self.native[1]]]:
            with self.subTest(records=records), self.assertRaises(ValueError):
                align_cases(['one.pdf', 'two.pdf'], records, 'quality')

    def test_missing_quality_case_fails(self):
        with self.assertRaises(ValueError):align_cases(['one.pdf', 'two.pdf'], self.native[:-1], 'quality')

    def test_changed_page_identity_fails(self):
        after = copy.deepcopy(self.native);after[0]['parsed']['pages'][1]['pageNumber'] = 3
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, after)

    def test_changed_page_box_fails(self):
        after = copy.deepcopy(self.native);after[0]['parsed']['pages'][0]['physicalBox'][2] = 211
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, after)

    def test_missing_native_block_fails(self):
        after = copy.deepcopy(self.native);after[1]['parsed']['pages'][0]['textBlocks'] = []
        with self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, after)

    def test_native_whitespace_and_font_size_changes_fail(self):
        for field in ['text', 'size']:
            after = copy.deepcopy(self.native);block = after[0]['parsed']['pages'][0]['textBlocks'][0]
            if field == 'text':block['text'] = 'ID00731 .28'
            else:block['style']['sizePt'] = 13
            with self.subTest(field=field), self.assertRaises(ValueError):require_native_geometry(self.truth, self.native, after)


if __name__ == '__main__':unittest.main()
