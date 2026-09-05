require 'minitest/autorun'
require 'yaml'

class HealthSensorsTest < Minitest::Test
  ROOT = File.expand_path(__dir__)
  PACKAGE = YAML.load_file(File.join(ROOT, 'frame_companion.yaml'))
  DASHBOARD = YAML.load_file(File.join(ROOT, 'frame_companion_dashboard.yaml'))
  HEALTH_NAMES = ['Frame Last Visible Render', 'Frame Recovery Count', 'Frame Last Failure'].freeze

  def sensors
    @sensors ||= PACKAGE.fetch('template').first.fetch('sensor').to_h { |sensor| [sensor.fetch('name'), sensor] }
  end

  def test_health_sensors_are_dashboarded_and_use_the_private_rest_snapshot
    entities = DASHBOARD.fetch('views').first.fetch('cards').first.fetch('entities')
    ['sensor.frame_last_visible_render', 'sensor.frame_recovery_count', 'sensor.frame_last_failure'].each do |entity|
      assert_includes entities, entity
    end

    HEALTH_NAMES.each do |name|
      sensor = sensors.fetch(name)
      availability = sensor.fetch('availability')
      assert_includes availability, "sensor.frame_companion"
      assert_includes availability, "serverTime"
      assert_includes availability, "lastSeenAt"
      assert_includes availability, "device.get('online', false)"
      assert_includes availability, "get('offline', false)"
      assert_includes availability, 'last_updated'
    end
  end

  def test_last_visible_render_is_a_timestamp_and_rejects_future_values
    sensor = sensors.fetch('Frame Last Visible Render')
    assert_equal 'timestamp', sensor.fetch('device_class')
    assert_includes sensor.fetch('state'), "get('lastPaintAt')"
    assert_includes sensor.fetch('state'), 'value <= now_ms'
    assert_includes sensor.fetch('availability'), "get('lastPaintAt')"
    assert_includes sensor.fetch('availability'), 'value <= now_ms'
  end

  def test_recovery_and_last_failure_require_present_typed_values
    recovery = sensors.fetch('Frame Recovery Count')
    failure = sensors.fetch('Frame Last Failure')

    assert_includes recovery.fetch('state'), "get('recoveryCount')"
    assert_includes recovery.fetch('availability'), "get('recoveryCount')"
    assert_includes recovery.fetch('availability'), 'value is number'
    assert_includes failure.fetch('state'), "get('lastError')"
    assert_includes failure.fetch('availability'), "get('lastError')"
    assert_includes failure.fetch('availability'), 'value is string'
  end
end
