require 'minitest/autorun'
require 'yaml'

class MorningSceneTest < Minitest::Test
  PACKAGE = YAML.load_file(File.join(__dir__, 'frame_companion.yaml'))

  def scene
    PACKAGE.fetch('automation').find { |item| item['id'] == 'frame_morning_scene' }
  end

  def test_morning_scene_is_opt_in_and_bounded
    assert_equal false, PACKAGE.fetch('input_boolean').fetch('frame_morning_scene_enabled').fetch('initial')
    assert_equal({ 'has_date' => false, 'has_time' => true, 'initial' => '07:00:00' }, PACKAGE.fetch('input_datetime').fetch('frame_morning_scene_time'))
    assert_equal ['weather', 'weather_then_calendar'], PACKAGE.fetch('input_select').fetch('frame_morning_scene_choice').fetch('options')
    assert_equal 'single', scene.fetch('mode')
    assert_equal({ 'trigger' => 'time', 'at' => 'input_datetime.frame_morning_scene_time' }, scene.fetch('triggers').first)
    assert_includes scene.fetch('conditions')[2].fetch('value_template'), 'photosPaused'
    assert_includes scene.fetch('conditions')[2].fetch('value_template'), 'offline'
    actions = YAML.dump(scene.fetch('actions'))
    assert_includes actions, '00:05:00'
    assert_includes actions, '00:02:30'
    refute_includes actions, 'photo_hold'
  end

  def test_morning_scene_only_returns_from_its_expected_view
    actions = YAML.dump(scene.fetch('actions'))
    assert_includes actions, "get('mode') == 'weather'"
    assert_includes actions, "get('mode') == 'calendar'"
    assert_includes actions, 'input_boolean.frame_morning_scene_enabled'
  end
end
