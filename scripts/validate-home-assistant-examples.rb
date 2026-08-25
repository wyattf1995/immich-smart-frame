# frozen_string_literal: true

require "json"
require "open3"
require "set"
require "yaml"

REPO_ROOT = File.expand_path("..", __dir__)
EXAMPLE_ROOT = File.join(REPO_ROOT, "examples", "home-assistant-wall-panel")
FILES = {
  dashboard: File.join(EXAMPLE_ROOT, "dashboard.example.yaml"),
  keymapper: File.join(EXAMPLE_ROOT, "keymapper-navigation.example.json"),
  loop_builder: File.join(EXAMPLE_ROOT, "build-weather-loops.sh"),
  weather_readme: File.join(EXAMPLE_ROOT, "weather", "README.md"),
  guide: File.join(REPO_ROOT, "docs", "home-assistant-wall-panel.md")
}.freeze

EXPECTED_FILES = FILES.values.map { |path| path.delete_prefix("#{REPO_ROOT}/") }.sort.freeze
EXPECTED_VIEW_PATHS = %w[home weather cameras calendar].freeze
EXPECTED_ENTITIES = Set[
  "binary_sensor.internet_status",
  "calendar.birthdays",
  "calendar.family",
  "calendar.holidays",
  "calendar.personal",
  "camera.camera_1",
  "camera.camera_2",
  "camera.camera_3",
  "camera.camera_4",
  "sensor.date",
  "sensor.last_successful_backup",
  "sensor.robot_battery",
  "sensor.robot_cleaning_progress",
  "sensor.sun_next_rising",
  "sensor.sun_next_setting",
  "sensor.time",
  "vacuum.robot_cleaner",
  "weather.home"
].freeze
EXPECTED_WEATHER_STATES = Set[
  "clear-night",
  "cloudy",
  "exceptional",
  "fog",
  "hail",
  "lightning",
  "lightning-rainy",
  "partlycloudy",
  "pouring",
  "rainy",
  "snowy",
  "snowy-rainy",
  "sunny",
  "windy",
  "windy-variant"
].freeze
PRIVATE_PATTERNS = {
  "RFC1918 address" => /\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\./,
  "absolute macOS user path" => %r{/Us[e]rs/},
  "personal Gmail address" => /[[:alnum:]_.+-]+@gmail\.com/i,
  "private Tailscale hostname" => /tail[[:alnum:]-]+\.ts\.net/i,
  "OAuth or API credential field" => /(?:access_token|client_secret|refresh_token)\s*[:=]/i
}.freeze

def fail_validation(message)
  warn "Home Assistant companion validation failed: #{message}"
  exit 1
end

FILES.each_value do |path|
  fail_validation("missing #{path.delete_prefix("#{REPO_ROOT}/")}") unless File.file?(path)
end

tracked_output, tracked_status = Open3.capture2(
  "git", "-C", REPO_ROOT, "ls-files", "examples/home-assistant-wall-panel"
)
fail_validation("could not list tracked example files") unless tracked_status.success?
actual_files = tracked_output.lines.map(&:strip).reject(&:empty?).sort
unless actual_files == EXPECTED_FILES.reject { |path| path.start_with?("docs/") }
  fail_validation("unexpected example file set #{actual_files.inspect}")
end

FILES.each do |name, path|
  contents = File.read(path)
  PRIVATE_PATTERNS.each do |description, pattern|
    fail_validation("#{name} contains a #{description}") if contents.match?(pattern)
  end
end

dashboard = YAML.load_file(FILES.fetch(:dashboard))
views = dashboard["views"]
fail_validation("dashboard views must be an array") unless views.is_a?(Array)

dashboard_nodes = lambda do |value|
  case value
  when Hash
    [value] + value.values.flat_map { |child| dashboard_nodes.call(child) }
  when Array
    value.flat_map { |child| dashboard_nodes.call(child) }
  else
    []
  end
end
if dashboard_nodes.call(dashboard).any? { |node| node.dig("tap_action", "action") == "more-info" }
  fail_validation("display-only wall-panel cards must not open a more-info dialog")
end

view_paths = views.map { |view| view["path"] }
unless view_paths == EXPECTED_VIEW_PATHS
  fail_validation("view paths #{view_paths.inspect}, expected #{EXPECTED_VIEW_PATHS.inspect}")
end

home_view, weather_view, camera_view, calendar_view = views
unless home_view["type"] == "sections" && home_view["max_columns"] == 2 &&
       home_view["animated_background"] == "weather"
  fail_validation("Home must remain the verified two-column weather view")
end
unless weather_view["type"] == "sections" && weather_view["max_columns"] == 2 &&
       weather_view["animated_background"] == "weather"
  fail_validation("Weather must be a two-column forecast view with a weather-driven background")
end

weather_cards = weather_view.fetch("sections", []).flat_map { |section| section.fetch("cards", []) }
weather_forecasts = weather_cards.select { |card| card["type"] == "weather-forecast" }
forecast_types = weather_forecasts.map { |card| card["forecast_type"] }.compact.to_set
hourly_forecast = weather_forecasts.find { |card| card["forecast_type"] == "hourly" }
unless weather_forecasts.any? { |card| card["show_current"] == true } &&
       forecast_types == Set["daily", "hourly"]
  fail_validation("Weather must show current conditions plus daily and hourly forecasts")
end
unless hourly_forecast && hourly_forecast["forecast_slots"] == 12
  fail_validation("Weather must expose a twelve-slot hourly forecast")
end

history_card = weather_cards.find { |card| card["type"] == "history-graph" }
unless history_card && history_card["hours_to_show"] == 24 &&
       history_card.fetch("entities", []).include?("weather.home")
  fail_validation("Weather must retain a 24-hour condition history")
end

activity_card = weather_cards.find { |card| card["type"] == "logbook" }
unless activity_card && activity_card["hours_to_show"] == 24 &&
       activity_card.dig("target", "entity_id") == ["weather.home"]
  fail_validation("Weather must show the recent condition activity")
end
unless camera_view["type"] == "panel" && camera_view["animated_background"] == "neutral"
  fail_validation("Cameras must remain a neutral-background panel view")
end
camera_cards = dashboard_nodes.call(camera_view).select { |node| node["type"] == "picture-entity" }
unless camera_cards.length == 4 && camera_cards.all? { |card| card.dig("tap_action", "action") == "none" }
  fail_validation("all four display-only camera cards must disable tap actions")
end
unless calendar_view["type"] == "panel" && calendar_view["animated_background"] == "neutral"
  fail_validation("Calendar must remain a neutral-background panel view")
end

calendar_card = calendar_view.fetch("cards", []).first
unless calendar_card.is_a?(Hash) && calendar_card["type"] == "calendar" &&
       calendar_card["initial_view"] == "dayGridMonth"
  fail_validation("Calendar must default to the verified month view")
end

entity_ids = File.read(FILES.fetch(:dashboard))
                 .scan(/\b(?:binary_sensor|calendar|camera|sensor|vacuum|weather)\.[a-z0-9_]+\b/)
                 .to_set
unless entity_ids == EXPECTED_ENTITIES
  fail_validation("entity placeholders #{entity_ids.to_a.sort.inspect}, expected #{EXPECTED_ENTITIES.to_a.sort.inspect}")
end

background = dashboard["animated_background"]
unless background.is_a?(Hash) && background["transparent_panel"] == true
  fail_validation("dashboard must retain the transparent animated-background configuration")
end

groups = background.fetch("groups", []).to_h { |group| [group["name"], group["config"]] }
unless groups.keys.sort == %w[neutral weather]
  fail_validation("animated-background groups must be neutral and weather")
end

weather_group = groups.fetch("weather")
unless weather_group["entity"] == "weather.home"
  fail_validation("weather background must be driven by weather.home")
end
weather_states = weather_group.fetch("state_url", {}).keys.to_set
unless weather_states == EXPECTED_WEATHER_STATES
  fail_validation("weather states #{weather_states.to_a.sort.inspect}, expected #{EXPECTED_WEATHER_STATES.to_a.sort.inspect}")
end

background_urls = [background["default_url"], weather_group["default_url"], *weather_group.fetch("state_url").values]
unless background_urls.all? { |url| url.is_a?(String) && url.start_with?("/local/wallpanel-weather/") }
  fail_validation("all background assets must use local /local/wallpanel-weather/ URLs")
end

keymapper = JSON.parse(File.read(FILES.fetch(:keymapper)))
unless keymapper["keymap_db_version"] == 22 && keymapper["app_version"] == 259
  fail_validation("Key Mapper export must remain compatible with verified app/database versions")
end

keymaps = keymapper["keymap_list"]
fail_validation("Key Mapper export must contain three mappings") unless keymaps.is_a?(Array) && keymaps.length == 3

actual_mappings = keymaps.map do |keymap|
  trigger_key = keymap.dig("trigger", "keys")&.first
  action = keymap.fetch("actionList", []).first
  meta_state = action&.fetch("extras", [])&.find { |extra| extra["id"] == "extra_meta_state" }
  [trigger_key&.slice("keyCode", "scanCode"), action&.slice("type", "data"), meta_state&.fetch("data", nil)]
end
expected_mappings = [
  [{ "keyCode" => 25 }, { "type" => "KEY_EVENT", "data" => "61" }, "0"],
  [{ "keyCode" => 24 }, { "type" => "KEY_EVENT", "data" => "61" }, "1"],
  [{ "keyCode" => 0, "scanCode" => 255 }, { "type" => "KEY_EVENT", "data" => "66" }, "0"]
]
unless actual_mappings == expected_mappings
  fail_validation("Key Mapper actions no longer encode Volume Down=Tab, Volume Up=Shift+Tab, star=Enter")
end

uids = keymapper.to_s.scan(/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/i)
unless uids.length == uids.uniq.length && uids.all? { |uid| uid.match?(/\A[0-9a-f-]{36}\z/i) }
  fail_validation("Key Mapper UUIDs must be valid and unique")
end

loop_builder = File.read(FILES.fetch(:loop_builder))
%w[sunny cloudy rainy clear-night].each do |weather|
  fail_validation("loop builder is missing #{weather}") unless loop_builder.include?(weather)
end
fail_validation("loop builder must use a portable script-relative default") unless loop_builder.include?('script_dir')
fail_validation("loop builder must keep foreground terrain fixed") if loop_builder.include?("zoompan")
unless loop_builder.include?("between(t,7,9)") && loop_builder.include?("between(t,21,22.8)")
  fail_validation("clear-night loop must retain two occasional shooting-star windows")
end

guide = File.read(FILES.fetch(:guide))
required_architecture_terms = ["UNVERIFIED", "FrameOS", "GeckoView", "Firefox", "Fully Kiosk"]
unless required_architecture_terms.all? { |term| guide.include?(term) }
  fail_validation("guide must preserve physical verification boundaries, FrameOS, and legacy rollback")
end

weather_readme = File.read(FILES.fetch(:weather_readme))
%w[sunny.png cloudy.png rainy.png clear-night.png neutral.png].each do |filename|
  fail_validation("weather README is missing #{filename}") unless weather_readme.include?(filename)
end

puts "Home Assistant companion examples are structurally valid and privacy-safe"
