#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

root = File.expand_path("..", __dir__)
html_path = File.join(root, "examples", "frameos", "frameos-touchscreen-panel.html")
yaml_path = File.join(root, "examples", "home-assistant-wall-panel", "touchscreen-dashboard.example.yaml")
guide_path = File.join(root, "docs", "frameos-touchscreen-panel.md")

[html_path, yaml_path, guide_path].each { |path| abort("missing #{path}") unless File.file?(path) }
html = File.read(html_path)
dashboard = YAML.load_file(yaml_path)
guide = File.read(guide_path)

required_html = [
  "default-src 'none'", "frame-src 'self'", "referrerpolicy=\"same-origin\"",
  '"/frameos-touchscreen/home?kiosk"', '"/frameos-touchscreen/cameras?kiosk"',
  '"/frameos-touchscreen/calendar?kiosk"', "READY_TIMEOUT_MILLIS", "MutationObserver",
  "disposeRouteWork", "iframeView", "observeRoot", "scheduleShadowProbe", "shadowProbeExpired", "record.addedNodes", "location-changed", "hui-picture-entity-card", "Sign-in required", "still connecting"
]
required_html.each { |value| abort("touchscreen wrapper missing #{value}") unless html.include?(value) }
abort("touchscreen wrapper must have exactly one iframe") unless html.scan(/<iframe\b/).size == 1
%w[fetch( XMLHttpRequest localStorage sessionStorage window.open http://].each do |forbidden|
  abort("touchscreen wrapper contains forbidden #{forbidden}") if html.include?(forbidden)
end

views = dashboard.fetch("views")
paths = views.map { |view| view.fetch("path") }
expected_paths = %w[home cameras camera-1 camera-2 camera-3 camera-4 calendar]
abort("unexpected touchscreen paths #{paths.inspect}") unless paths == expected_paths

home = views.first
home_text = home.to_s
%w[Today Up\ next Home\ status Comfort climate.entryway_entryway].each do |value|
  abort("Home dashboard missing #{value}") unless home_text.include?(value)
end
abort("dashboard must not automate thermostat service calls") if home_text.match?(/service:|service_data:|perform-action/)
%w[binary_sensor.internet_status sensor.last_successful_backup].each do |entity|
  abort("Home dashboard must not retain unverified #{entity}") if home_text.include?(entity)
end
home_status_markdown = home.dig("sections", 1, "cards")&.find { |card| card["type"] == "markdown" }&.fetch("content", "")
abort("Home status must use the verified thermostat state") unless home_status_markdown&.include?("climate.entryway_entryway")
camera_status_entities = %w[
  camera.backyard_backyard_camera
  camera.dining_room_dining_room_camera
  camera.garage_garage_camera
  camera.living_room_living_room_camera
]
unless home_status_markdown&.include?("select('has_value')") && camera_status_entities.all? { |entity| home_status_markdown.include?(entity) }
  abort("Home status must derive camera availability from the four verified camera entities")
end

camera_chooser = views[1]
camera_chooser_cards = camera_chooser.fetch("sections").flat_map { |section| section.fetch("cards") }
abort("camera chooser must not mount live video") if camera_chooser_cards.any? { |card| card["camera_view"] == "live" }
chooser_targets = camera_chooser_cards.map { |card| card.dig("tap_action", "navigation_path") }.compact
expected_chooser_targets = %w[/frameos-touchscreen/camera-1?kiosk /frameos-touchscreen/camera-2?kiosk /frameos-touchscreen/camera-3?kiosk /frameos-touchscreen/camera-4?kiosk]
abort("camera chooser navigation targets are incomplete") unless chooser_targets == expected_chooser_targets
camera_views = views[2, 4]
abort("need four camera detail views") unless camera_views.length == 4
camera_entities = %w[
  camera.backyard_backyard_camera
  camera.dining_room_dining_room_camera
  camera.garage_garage_camera
  camera.living_room_living_room_camera
]
camera_views.each_with_index do |view, index|
  text = view.to_s
  entity = camera_entities.fetch(index)
  abort("camera detail lacks #{entity}") unless text.include?(entity)
  abort("camera detail must be a Home Assistant subview") unless view["subview"] == true
  live_cards = view.fetch("cards").select { |card| card["camera_view"] == "live" }
  abort("camera detail must mount exactly one live stream") unless live_cards.length == 1
  abort("camera detail needs a chooser return path") unless text.include?("/frameos-touchscreen/cameras?kiosk")
end

private_patterns = [/%r\{\/Users\/\}/, /192\.168\./, /access_token/i, /client_secret/i, /tail[[:alnum:]-]+\.ts\.net/i]
[html, File.read(yaml_path), guide].each do |contents|
  private_patterns.each { |pattern| abort("touchscreen artifact contains private material") if contents.match?(pattern) }
end
abort("guide must disclose QA checks") unless guide.include?("QA frame") && guide.include?("Android 6")

puts "FrameOS touchscreen panel structure and privacy validation passed"
