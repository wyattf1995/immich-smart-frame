import unittest

from tagging.filename_backfill import should_backfill_screenshot


class FilenameBackfillTests(unittest.TestCase):
    def test_adds_only_missing_screenshot_skip_tag(self):
        self.assertTrue(should_backfill_screenshot('2026-09-05_151144_Screenshot.png', ('Scene/Indoor',)))
        self.assertFalse(should_backfill_screenshot('Screenshot_2026-09-05.png', ('Skip/Screenshot',)))

    def test_never_selects_lookalike_photo_filename(self):
        self.assertFalse(should_backfill_screenshot('family-screenshotphoto-inside.jpg', ()))


if __name__ == '__main__':
    unittest.main()
