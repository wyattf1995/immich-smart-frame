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
  "const waitForRouteReady = (readyView) =>",
  "ROUTE_READY_TIMEOUT_MILLIS",
  "const readinessRequirements = Object.freeze({",
  'primaryHeadings: Object.freeze(["today", "up next", "home status"])',
  "primaryHeadings",
  "cameraCards",
  "cameraPlayers",
  "cameraVideos: 4",
  "decodedVideos",
  "new MutationObserver(",
  "const inspectAddedSubtree = (node, state) =>",
  "const cancelRouteWork = () =>",
  "const isActivePanelElement = (element, documentRoot) =>",
  "element.isConnected && element.ownerDocument === documentRoot",
  "const pruneRouteEvidence = (state) =>",
  "!cameraVideoIsDecoded(video)",
  "const pruneRouteObservers = (state) =>",
  "state.observers.push({ root, observer });",
  "primaryHeadings: new Map()",
  "const disconnectRouteObservers = (state) =>",
  "const removeVideoListeners = (state) =>",
  "new WeakSet()",
  "still connecting",
  "panel.src = nextPath",
]
required.each do |contract|
  abort("FrameOS panel wrapper missing contract: #{contract}") unless panel.include?(contract)
end

abort("FrameOS panel wrapper must contain exactly one iframe") unless panel.scan(/<iframe\b/).size == 1
iframe_tag = panel.match(/<iframe\b[^>]*>/m)&.[](0)
abort("FrameOS panel wrapper iframe must select its initial route dynamically") if iframe_tag&.match?(/\bsrc\s*=/)
abort("FrameOS panel wrapper must not expose a weather web route") if panel.include?("/wall-panel/weather")
abort("FrameOS panel wrapper must not include browser toolbar workarounds") if panel.match?(/requestFullscreen|scroll-rail|min-height:\s*calc/)
abort("FrameOS panel wrapper must not declare readiness on a fixed 180ms delay") if panel.match?(/setTimeout\(\(\)\s*=>\s*\{\s*showReady.*?180\s*\)/m)
abort("FrameOS panel wrapper must not retry full-DOM readiness scans every 100ms") if panel.include?("ROUTE_READY_POLL_MILLIS")
abort("FrameOS panel wrapper must use its route observer for heading contrast") if panel.include?("startHeadingContrast")
abort("FrameOS panel wrapper must disconnect route observers when superseded") unless panel.include?("observer.disconnect()")
abort("FrameOS panel wrapper must discard strong camera evidence after ready") unless panel.include?("releaseRouteEvidence(state)")
abort("FrameOS panel wrapper must require all four decoded camera videos") unless panel.include?("state.cameraVideos.size >= requirement.cameraVideos")
abort("FrameOS panel wrapper must not treat absent camera videos as ready") if panel.include?("state.cameraVideos.size === 0")
unless panel.match?(/showStillConnecting\(state\.view, detail\);\s*focusPanel\(\);/m)
  abort("FrameOS panel wrapper must focus the panel after a nonblocking readiness timeout")
end
wait_for_route_ready = panel[/const waitForRouteReady = \(readyView\) => \{.*?\n        \};\n\n        const navigatePanel/m]
abort("FrameOS panel wrapper is missing waitForRouteReady") unless wait_for_route_ready
deadline_index = wait_for_route_ready.index("state.deadlineTimer = window.setTimeout")
initial_inspection_index = wait_for_route_ready.index("inspectAddedSubtree(documentRoot, state)")
unless deadline_index && initial_inspection_index && deadline_index < initial_inspection_index
  abort("FrameOS panel wrapper must arm the deadline before synchronous initial readiness inspection")
end

forbidden = [
  "fetch(",
  "XMLHttpRequest",
  "localStorage",
  "sessionStorage",
  "console.",
  "window.open",
  "setInterval(",
  "http://",
]
forbidden.each do |value|
  abort("FrameOS panel wrapper contains forbidden behavior: #{value}") if panel.include?(value)
end

puts "FrameOS panel wrapper validation passed"
