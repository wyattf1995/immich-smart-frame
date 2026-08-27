#!/usr/bin/env ruby
# frozen_string_literal: true

root = File.expand_path("..", __dir__)
surface_path = File.join(
  root,
  "frameos/app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt",
)
abort("missing FrameOS web surface: #{surface_path}") unless File.file?(surface_path)

surface = File.read(surface_path)
required = [
  "pendingCameraClosures",
  "post(close)",
  "removeCallbacks(callback)",
]
required.each do |contract|
  abort("FrameOS camera disposal missing contract: #{contract}") unless surface.include?(contract)
end

abort("camera teardown must not close synchronously in disposeCameraSession") if surface.include?(
  "\n        disposable.close()\n",
)

puts "FrameOS camera disposal validation passed"
