#!/usr/bin/env ruby
# frozen_string_literal: true

root = File.expand_path("..", __dir__)
manifest_path = File.join(root, "frameos", "app", "src", "main", "AndroidManifest.xml")
receiver_path = File.join(
  root,
  "frameos",
  "app",
  "src",
  "main",
  "kotlin",
  "com",
  "wyattfleming",
  "frameos",
  "control",
  "FrameControlReceiver.kt",
)

abort("missing FrameOS control receiver: #{receiver_path}") unless File.file?(receiver_path)

manifest = File.read(manifest_path)
receiver = File.read(receiver_path)

manifest_contracts = [
  'android:name=".control.FrameControlReceiver"',
  'android:exported="true"',
  'android:permission="android.permission.DUMP"',
  'android:name="com.wyattfleming.frameos.CONTROL"',
]
manifest_contracts.each do |contract|
  abort("FrameOS manifest missing protected control contract: #{contract}") unless manifest.include?(contract)
end

receiver_contracts = [
  "FrameControlCommandCodec",
  "FrameControlStore",
  "FrameConfigurationStore",
  "Intent(context, MainActivity::class.java)",
]
receiver_contracts.each do |contract|
  abort("FrameOS control receiver missing contract: #{contract}") unless receiver.include?(contract)
end

forbidden = ["Runtime.getRuntime", "ProcessBuilder", "su ", "http://", "https://"]
forbidden.each do |value|
  abort("FrameOS control receiver contains forbidden behavior: #{value}") if receiver.include?(value)
end

puts "FrameOS protected control receiver validation passed"
