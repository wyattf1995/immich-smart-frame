#!/usr/bin/env ruby
# frozen_string_literal: true

root = File.expand_path("..", __dir__)
activity_path = File.join(
  root,
  "frameos/app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt",
)
abort("missing FrameOS activity: #{activity_path}") unless File.file?(activity_path)

activity = File.read(activity_path)
texture_backend = "setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)"
abort("warm Home Assistant surface must use the composited TextureView backend") unless activity.include?(texture_backend)
abort("only the warm Home Assistant surface should opt into TextureView") unless activity.scan(texture_backend).size == 1

warm_layer = activity.index("root.addView(warmHomeAssistantView")
primary_layer = activity.index("root.addView(webSurface")
weather_layer = activity.index("root.addView(weatherContent")
abort("FrameOS web layers are missing") unless warm_layer && primary_layer && weather_layer
abort("warm HA, primary web, and native weather layers must remain back-to-front") unless warm_layer < primary_layer && primary_layer < weather_layer

puts "FrameOS surface layering validation passed"
