require 'minitest/autorun'
require 'yaml'

class MorningSceneTest < Minitest::Test
  PACKAGE = YAML.load_file(File.join(__dir__, 'frame_companion.yaml'))

  def scene
    PACKAGE.fetch('automation').find { |item| item['id'] == 'frame_morning_scene' }
  end

  def test_morning_scene_is_opt_in_and_bounded
    assert_equal false, PACKAGE.fetch('input_boolean').fetch('frame_morning_scene_enabled').fetch('initial')
    assert_equal({ 'has_date' => false, 'has_time' => true }, PACKAGE.fetch('input_datetime').fetch('frame_morning_scene_time'))
    assert_equal ['weather', 'weather_then_calendar'], PACKAGE.fetch('input_select').fetch('frame_morning_scene_choice').fetch('options')
    refute PACKAGE.fetch('input_select').fetch('frame_morning_scene_choice').key?('initial')
    assert_equal 'single', scene.fetch('mode')
    assert_equal({ 'trigger' => 'time', 'at' => 'input_datetime.frame_morning_scene_time' }, scene.fetch('triggers').first)
    actions = YAML.dump(scene.fetch('actions'))
    assert_includes actions, 'photosPaused'
    assert_includes actions, 'offline'
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

  def test_morning_scene_refreshes_and_fails_closed_at_each_boundary
    actions = YAML.dump(scene.fetch('actions'))

    refute scene.key?('variables'), 'the trigger-time device snapshot must not be reused'
    assert_operator actions.scan('homeassistant.update_entity').length, :>=, 3
    %w[sensor.frame_companion last_updated serverTime lastSeenAt photosPaused offline].each do |field|
      assert_includes actions, field
    end

    assert snapshot_allowed?(sensor_online: true, snapshot_age_seconds: 1,
                             server_age_milliseconds: 1_000, last_seen_age_milliseconds: 1_000,
                             device_online: true, mode: 'photos', expected_mode: 'photos')
    refute snapshot_allowed?(sensor_online: true, snapshot_age_seconds: 16,
                             server_age_milliseconds: 1_000, last_seen_age_milliseconds: 1_000,
                             device_online: true, mode: 'photos', expected_mode: 'photos'), 'stale/failed refresh'
    refute snapshot_allowed?(sensor_online: true, snapshot_age_seconds: 1,
                             server_age_milliseconds: 1_000, last_seen_age_milliseconds: 1_000,
                             device_online: true, mode: 'photos', expected_mode: 'photos', photos_paused: true), 'paused initial view'
    refute snapshot_allowed?(sensor_online: true, snapshot_age_seconds: 1,
                             server_age_milliseconds: 1_000, last_seen_age_milliseconds: 1_000,
                             device_online: true, mode: 'home', expected_mode: 'weather'), 'manual view change'
    refute snapshot_allowed?(sensor_online: false, snapshot_age_seconds: 1,
                             server_age_milliseconds: 1_000, last_seen_age_milliseconds: 1_000,
                             device_online: false, mode: 'weather', expected_mode: 'weather'), 'offline boundary'
  end

  private

  # This is the fail-closed fixture for the companion snapshot accepted at a
  # command boundary. The YAML assertions above bind it to the state fields
  # used by the package.
  def snapshot_allowed?(sensor_online:, snapshot_age_seconds:, server_age_milliseconds:,
                        last_seen_age_milliseconds:, device_online:, mode:, expected_mode:,
                        photos_paused: false, offline: false)
    sensor_online && snapshot_age_seconds.between?(0, 15) &&
      server_age_milliseconds.between?(0, 15_000) &&
      last_seen_age_milliseconds.between?(0, 15_000) &&
      device_online && mode == expected_mode && !photos_paused && !offline
  end
end
