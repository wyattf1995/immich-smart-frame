#!/usr/bin/env ruby
# frozen_string_literal: true

root = File.expand_path("..", __dir__)
callback_path = File.join(root, "examples", "frameos", "frameos-oauth.html")
abort("missing FrameOS OAuth callback: #{callback_path}") unless File.file?(callback_path)

callback = File.read(callback_path)
required = [
  "default-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "referrer",
  "no-referrer",
  'http-equiv="Cache-Control"',
  'content="no-store, no-cache, must-revalidate"',
  '<link rel="redirect_uri" href="frameos://oauth/callback">',
  "new URL(\"frameos://oauth/callback\")",
  "searchParams.set(\"code\"",
  "searchParams.set(\"state\"",
  "location.replace(target.toString())",
  'history.replaceState(null, "", source.pathname)',
]
required.each do |contract|
  abort("FrameOS OAuth callback missing contract: #{contract}") unless callback.include?(contract)
end

forbidden = ["innerHTML", "console.", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "http://"]
forbidden.each do |value|
  abort("FrameOS OAuth callback contains forbidden behavior: #{value}") if callback.include?(value)
end

abort("FrameOS OAuth callback must forward both code and state exactly once") unless callback.scan(/searchParams\.set\("(?:code|state)"/).size == 2

puts "FrameOS OAuth callback validation passed"
