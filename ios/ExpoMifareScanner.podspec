require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'ExpoMifareScanner'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author        = package['author']
  s.homepage      = package['repository']['url']
  s.platforms     = { :ios => '15.0' }
  s.swift_version = '5.0'
  s.source         = { :path => '.' }
  s.source_files   = 'ExpoMifareScanner/**/*.{swift,h,m}'
  s.weak_frameworks = 'CoreNFC'
  s.dependency 'ExpoModulesCore'
end
