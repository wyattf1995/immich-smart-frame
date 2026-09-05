import io
import unittest

from tagging.filename_backfill import BackfillRequestError, batches, read_candidates


class StdinCandidateBackfillTests(unittest.TestCase):
    def test_reads_only_explicit_candidate_rows(self):
        rows = read_candidates(io.StringIO('{"id":"a","originalFileName":"Screenshot_2026-09-05.png"}\n'), 5000)
        self.assertEqual((('a', 'Screenshot_2026-09-05.png'),), rows)

    def test_rejects_malformed_candidate_without_fallback_scan(self):
        with self.assertRaises(BackfillRequestError):
            read_candidates(io.StringIO('{"id":"a"}\n'), 5000)

    def test_apply_batches_never_exceed_500(self):
        grouped = list(batches([str(n) for n in range(1001)], 500))
        self.assertEqual([500, 500, 1], [len(group) for group in grouped])


if __name__ == '__main__':
    unittest.main()
