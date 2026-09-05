import unittest

from tagging.policy import (
    ClassificationError,
    filename_skip_tag,
    plan_asset_update,
    require_vlm_classification,
)


class FilenamePolicyTests(unittest.TestCase):
    def test_strong_screenshot_names_skip_without_vlm(self):
        for name in (
            'Screenshot_2026-09-05_151144.png',
            'Screen Shot 2026-09-05 at 15.11.44.png',
            'ScreenCapture 20260905-151144.jpg',
            '2026-09-05_151144_Screenshot.png',
        ):
            self.assertEqual('Skip/Screenshot', filename_skip_tag(name), name)

    def test_screenshot_word_inside_a_photo_name_is_not_a_match(self):
        self.assertIsNone(filename_skip_tag('family-screenshotphoto-inside.jpg'))
        self.assertIsNone(filename_skip_tag('vacation_screenshot_reference.jpg'))


class VlmPolicyTests(unittest.TestCase):
    def test_skip_tags_survive_content_cap(self):
        plan = plan_asset_update(
            original_file_name='IMG_0001.jpg',
            vlm={'tags': [f'content-{n}' for n in range(20)], 'caption': 'A photo.', 'image_type': 'screenshot'},
            existing_tag_names=(),
            existing_description='',
        )
        self.assertIn('Skip/Screenshot', plan.add_tag_names)
        self.assertEqual(12, len(plan.desired_tag_names))
        self.assertEqual('Skip/Screenshot', plan.desired_tag_names[0])

    def test_missing_or_truncated_image_type_is_not_complete(self):
        with self.assertRaises(ClassificationError):
            require_vlm_classification({'tags': ['cat'], 'caption': 'A cat.'})
        with self.assertRaises(ClassificationError):
            require_vlm_classification({'tags': ['cat'], 'caption': 'A cat.', 'image_type': 'screen'})

    def test_human_caption_is_preserved_and_tagging_is_additive(self):
        plan = plan_asset_update(
            original_file_name='IMG_0001.jpg',
            vlm={'tags': ['forest', 'dog'], 'caption': 'A dog in a forest.', 'image_type': 'photograph'},
            existing_tag_names=('Animals/Dog',),
            existing_description='Human-written caption',
        )
        self.assertEqual(('Scene/Forest',), plan.add_tag_names)
        self.assertIsNone(plan.description_to_write)
        self.assertTrue(plan.complete)

    def test_existing_ai_caption_can_be_replaced(self):
        plan = plan_asset_update(
            original_file_name='IMG_0001.jpg',
            vlm={'tags': ['dog'], 'caption': 'A dog.', 'image_type': 'photograph'},
            existing_tag_names=(),
            existing_description='[AI] older caption [/AI]',
        )
        self.assertEqual('[AI] A dog. [/AI]', plan.description_to_write)


if __name__ == '__main__':
    unittest.main()
