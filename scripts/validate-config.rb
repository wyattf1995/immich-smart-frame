# frozen_string_literal: true

require "yaml"

ALLOWED_SOURCE_TYPES = %w[album person tag date memories random].freeze
VALUE_REQUIRED_TYPES = %w[album person tag date].freeze
DATE_VALUE = /\A(?:today|last-\d+|\d{4}-\d{2}-\d{2}_to_(?:today|\d{4}-\d{2}-\d{2}))\z/i
EXPECTED_SLIDE_DURATION = 45
EXPECTED_IMAGE_DATE_FORMAT = "YYYY-MM-DD"

def fail_config(file, message)
  warn "#{file}: #{message}"
  exit 1
end

ARGV.each do |file|
  config = YAML.load_file(file)
  unless config["duration"] == EXPECTED_SLIDE_DURATION
    fail_config(file, "duration must be #{EXPECTED_SLIDE_DURATION} seconds")
  end
  unless config["image_date_format"] == EXPECTED_IMAGE_DATE_FORMAT
    fail_config(file, "image_date_format must be #{EXPECTED_IMAGE_DATE_FORMAT.inspect}")
  end

  profiles = config.dig("curation", "profiles")
  fail_config(file, "curation.profiles must be a non-empty map") unless profiles.is_a?(Hash) && !profiles.empty?

  selected = config["curation_profile"]
  unless selected.is_a?(String) && profiles.keys.any? { |name| name.casecmp?(selected.strip) }
    fail_config(file, "curation_profile #{selected.inspect} does not name a profile")
  end

  profiles.each do |name, profile|
    sources = profile["sources"]
    fail_config(file, "profile #{name.inspect} has no sources") unless sources.is_a?(Array) && !sources.empty?

    total = 0
    sources.each_with_index do |source, index|
      type = source["type"].to_s.strip.downcase
      value = source["value"].to_s.strip
      weight = source["weight"]

      unless ALLOWED_SOURCE_TYPES.include?(type)
        fail_config(file, "profile #{name.inspect} source #{index} has unsupported type #{type.inspect}")
      end
      if VALUE_REQUIRED_TYPES.include?(type) && value.empty?
        fail_config(file, "profile #{name.inspect} source #{index} requires a value")
      end
      unless weight.is_a?(Numeric) && weight.positive?
        fail_config(file, "profile #{name.inspect} source #{index} needs a positive numeric weight")
      end
      if type == "date" && !DATE_VALUE.match?(value)
        fail_config(file, "profile #{name.inspect} source #{index} has invalid date #{value.inspect}")
      end

      total += weight
    end

    fail_config(file, "profile #{name.inspect} weights total #{total}, expected 100") unless total == 100
    puts "#{file}: #{name}=#{total}"
  end
end
