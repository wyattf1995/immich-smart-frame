import importlib
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

TAGGING = str(Path(__file__).resolve().parents[1])
if TAGGING not in sys.path:
    sys.path.insert(0, TAGGING)
os.environ.setdefault('IMMICH_KEY', 'test-only')
runner = importlib.import_module('tagger_policy')


class RunnerWriteGateTests(unittest.TestCase):
    def setUp(self):
        runner.TAGMAP.clear()

    def test_dry_direct_filename_plan_never_puts(self):
        with patch.object(runner, 'im') as request:
            runner.process('asset-id', 'Screenshot_2026-09-05.png', '', (), '', apply=False)
        request.assert_not_called()

    def test_apply_direct_filename_adds_only_skip_relationship(self):
        runner.TAGMAP['Skip/Screenshot'] = 'skip-tag-id'
        with patch.object(runner, 'im', return_value=None) as request:
            runner.process('asset-id', 'Screenshot_2026-09-05.png', '', (), 'Human caption', apply=True)
        request.assert_called_once_with('PUT', '/api/tags/assets', {'tagIds': ['skip-tag-id'], 'assetIds': ['asset-id']})

    def test_failed_apply_does_not_checkpoint(self):
        old = (runner.WORK, runner.CKPT, runner.LOGF, runner.LOCK, runner.APPLY, runner.DRY, runner.IDS_FILE)
        temp = Path(self._testMethodName)
        try:
            runner.WORK = str(temp)
            runner.CKPT = str(temp / 'processed.txt')
            runner.LOGF = str(temp / 'tagger.log')
            runner.LOCK = str(temp / 'tagger.lock')
            runner.APPLY = True
            runner.DRY = False
            runner.IDS_FILE = None
            with patch.object(runner, 'process', side_effect=runner.ClassificationError('bad response')):
                self.assertFalse(runner.record_success_after_process('asset-id', open_checkpoint=lambda: None))
        finally:
            (runner.WORK, runner.CKPT, runner.LOGF, runner.LOCK, runner.APPLY, runner.DRY, runner.IDS_FILE) = old


if __name__ == '__main__':
    unittest.main()

class RunnerSchemaAndOrderingTests(unittest.TestCase):
    def setUp(self):
        runner.TAGMAP.clear()

    def test_runner_keeps_late_skip_tag_before_content_cap(self):
        mapped = tuple([f'Content/{n}' for n in range(12)] + ['Skip/Blurry'])
        runner.TAGMAP.update({tag: f'id-{n}' for n, tag in enumerate(mapped)})
        response = {'tags': ['anything'], 'caption': 'caption', 'image_type': 'photograph'}
        with patch.object(runner, 'vlm', return_value=response), patch.object(runner, 'map_tags', return_value=mapped), patch.object(runner, 'im', return_value=None) as request:
            plan = runner.process('asset-id', 'IMG_0001.jpg', '', (), '', apply=True)
        self.assertIn('Skip/Blurry', plan.desired_tag_names)
        self.assertEqual('Skip/Blurry', plan.desired_tag_names[0])
        self.assertEqual(12, len(plan.desired_tag_names))
        write = request.call_args_list[0]
        self.assertEqual('PUT', write.args[0])
        self.assertIn('id-12', write.args[2]['tagIds'])

    def test_runner_schema_places_image_type_before_ocr_and_salvage_requires_it(self):
        self.assertLess(list(runner.SCHEMA['properties']).index('image_type'), list(runner.SCHEMA['properties']).index('ocr_text'))
        recovered = runner.parse_vlm('{"image_type":"screenshot","tags":["screen"],"caption":"A screen"')
        self.assertEqual('screenshot', recovered['image_type'])
        with self.assertRaises(Exception):
            runner.parse_vlm('{"tags":["screen"],"caption":"A screen"')

class RunnerExitStatusTests(unittest.TestCase):
    def test_id_mode_reports_failure_when_classification_fails(self):
        from tempfile import NamedTemporaryFile
        old = (runner.APPLY, runner.DRY, runner.IDS_FILE)
        with NamedTemporaryFile(mode='w', encoding='utf-8') as ids:
            ids.write('asset-id\n'); ids.flush()
            try:
                runner.APPLY, runner.DRY, runner.IDS_FILE = True, False, ids.name
                asset = {'originalFileName': 'IMG_0001.jpg', 'originalPath': '', 'tags': [], 'exifInfo': {}}
                with patch.object(runner, 'ensure_tags'), patch.object(runner, 'im', return_value=asset), patch.object(runner, 'record_success_after_process', return_value=False):
                    self.assertEqual(1, runner.main())
            finally:
                runner.APPLY, runner.DRY, runner.IDS_FILE = old

    def test_subprocess_invalid_mode_exits_nonzero_without_api_work(self):
        import subprocess
        env = dict(os.environ, IMMICH_KEY='test-only')
        result = subprocess.run([sys.executable, str(Path(runner.__file__))], env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        self.assertEqual(2, result.returncode)
        self.assertIn('explicit --apply is required', result.stdout)
        self.assertNotIn('http', result.stdout.lower() + result.stderr.lower())
