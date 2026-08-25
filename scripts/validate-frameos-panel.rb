#!/usr/bin/env ruby
# frozen_string_literal: true

root = File.expand_path("..", __dir__)
panel_path = File.join(root, "examples", "frameos", "frameos-panel.html")
abort("missing FrameOS panel wrapper: #{panel_path}") unless File.file?(panel_path)

panel = File.read(panel_path)

required = [
  "default-src 'none'",
  "frame-src 'self'",
  'new Set(["home", "cameras", "calendar"])',
  'home: "/wall-panel/home?kiosk"',
  'cameras: "/wall-panel/cameras?kiosk"',
  'calendar: "/wall-panel/calendar?kiosk"',
  "history.pushState",
  'new childWindow.Event("location-changed")',
  'window.addEventListener("hashchange"',
  'aria-live="polite"',
  'id="loading"',
  "panel.contentWindow.focus()",
]
required.each do |contract|
  abort("FrameOS panel wrapper missing contract: #{contract}") unless panel.include?(contract)
end

abort("FrameOS panel wrapper must contain exactly one iframe") unless panel.scan(/<iframe\b/).size == 1
abort("FrameOS panel wrapper must not expose a weather web route") if panel.include?("/wall-panel/weather")
abort("FrameOS panel wrapper must not include browser toolbar workarounds") if panel.match?(/requestFullscreen|scroll-rail|min-height:\s*calc/)

forbidden = [
  "fetch(",
  "XMLHttpRequest",
  "localStorage",
  "sessionStorage",
  "console.",
  "window.open",
  "http://",
]
forbidden.each do |value|
  abort("FrameOS panel wrapper contains forbidden behavior: #{value}") if panel.include?(value)
end

puts "FrameOS panel wrapper validation passed"
