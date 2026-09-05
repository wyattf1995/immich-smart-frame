#!/usr/bin/env ruby
require "yaml"
config = YAML.safe_load(File.read(File.join(__dir__, "../docker-compose.yaml")), aliases: true)
logging = config.fetch("services").fetch("immich-kiosk").fetch("logging")
options = logging.fetch("options", {})
if logging["driver"] == "local" && options["max-file"].to_s == "1" && options.fetch("compress", "true").to_s != "false"
  abort "Docker local logging cannot compress a single retained file"
end
puts "PASS: Kiosk log settings can start on Docker"
