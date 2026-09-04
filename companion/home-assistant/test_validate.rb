#!/usr/bin/env ruby
require 'yaml'
root = File.expand_path(__dir__)
Dir[File.join(root, '*.yaml')].each { |path| YAML.load_file(path) }
text = Dir[File.join(root, '*')].select { |p| File.file?(p) }.map { |p| File.read(p) }.join("\n")
abort 'unexpected raw bearer token' if text.match?(/Bearer (?!replace-with-private-token\b)[A-Za-z0-9._-]{12,}/)
puts 'home-assistant companion yaml and secret-placeholder checks passed'
