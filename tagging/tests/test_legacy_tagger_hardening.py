import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


os.environ.setdefault("IMMICH_KEY", "test-key")
SOURCE = Path(__file__).resolve().parents[1] / "legacy" / "tagger.py"
SPEC = importlib.util.spec_from_file_location("legacy_tagger_hardening", SOURCE)
tagger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tagger)


class FakeResponse:
    def __init__(self, body):
        self.body = body
        self.offset = 0

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, size):
        chunk = self.body[self.offset:self.offset + size]
        self.offset += len(chunk)
        return chunk


class LegacyTaggerHardeningTest(unittest.TestCase):
    def test_checkpoint_streams_legacy_file_and_keeps_text_compatibility(self):
        with tempfile.TemporaryDirectory() as directory:
            checkpoint_path = Path(directory) / "processed.txt"
            checkpoint_path.write_text("already-one\nalready-two\n")
            checkpoint = tagger.Checkpoint(str(checkpoint_path))
            self.assertTrue(checkpoint.contains("already-one"))
            self.assertEqual(2, checkpoint.count())
            checkpoint.mark("new-one")
            checkpoint.mark("new-one")
            self.assertEqual(3, checkpoint.count(), "SQLite remains the deduplicated restart index")
            checkpoint.close()

            reopened = tagger.Checkpoint(str(checkpoint_path))
            self.assertTrue(reopened.contains("new-one"))
            reopened.close()
            self.assertEqual("already-one\nalready-two\nnew-one\nnew-one\n", checkpoint_path.read_text())

    def test_http_rejects_an_oversized_response_before_returning_it(self):
        response = FakeResponse(b"x" * 17)
        with mock.patch.object(tagger.urllib.request, "urlopen", return_value=response):
            with self.assertRaises(tagger.ResponseTooLarge):
                tagger.http("GET", "http://test.invalid/metadata", timeout=1, max_bytes=16)

    def test_http_passes_the_declared_timeout_to_transport(self):
        response = FakeResponse(b"{}")
        with mock.patch.object(tagger.urllib.request, "urlopen", return_value=response) as urlopen:
            self.assertEqual(b"{}", tagger.http("GET", "http://test.invalid/metadata", timeout=7, max_bytes=16))
        self.assertEqual(7, urlopen.call_args.kwargs["timeout"])

    def test_response_budgets_remain_separate_for_metadata_model_and_preview(self):
        self.assertLess(tagger.MAX_MODEL_RESPONSE_BYTES, tagger.MAX_METADATA_RESPONSE_BYTES)
        self.assertGreater(tagger.MAX_PREVIEW_RESPONSE_BYTES, tagger.MAX_METADATA_RESPONSE_BYTES)


if __name__ == "__main__":
    unittest.main()
