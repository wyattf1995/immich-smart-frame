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
  "createCameraMountController", "disposeRouteWork", "still connecting"
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

camera_chooser = views[1].to_s
abort("camera chooser must not mount live video") if camera_chooser.include?("camera_view: live")
camera_views = views[2, 4]
abort("need four camera detail views") unless camera_views.length == 4
camera_views.each_with_index do |view, index|
  text = view.to_s
  entity = "camera.frameos_camera_#{index + 1}"
  abort("camera detail lacks #{entity}") unless text.include?(entity)
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
