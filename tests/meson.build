inc = include_directories('../src/common', '../src/streamer')
protocol_test = executable('protocol-test', ['protocol_test.cpp','../src/streamer/rtp_packetizer.cpp','../src/common/signaling.cpp'], include_directories:inc)
input_test = executable('input-state-test', 'input_state_test.cpp', include_directories:inc, dependencies:dependency('threads'))
test('protocol',protocol_test)
test('input-state',input_test)
encoder_test = executable('encoder-test', ['encoder_test.cpp','../src/streamer/encoder.cpp','../src/streamer/audio_encoder.cpp'], include_directories:inc,
  dependencies:[dependency('libavcodec'),dependency('libavutil'),dependency('libswscale'),dependency('libswresample')])
test('encoder-resize-audio',encoder_test,timeout:30)

security_test = executable('security-test', [
  'security/security_tests.cpp',
  '../src/common/secure_transport.cpp',
  '../src/common/signaling.cpp',
  '../src/streamer/signaling_server.cpp',
  '../src/streamer/rtp_server.cpp',
  '../src/streamer/rtp_packetizer.cpp',
], include_directories: inc, dependencies: [dependency('threads'), dependency('openssl')])
test('security', security_test, timeout: 60)

# Explicit real-compositor QA, always run through the isolated wrapper.
# Not a default test: needs existing Mutter, PipeWire, bwrap and private D-Bus.
if not mutter_opt.disabled() and have_dbus_pw
  gio_test_dep = dependency('gio-2.0', required : false)
  if gio_test_dep.found()
    executable('mutter-orientation-test', [
      'mutter_orientation_test.cpp',
      '../src/streamer/mutter_screencast.cpp',
      '../src/streamer/mutter_virtual_source.cpp',
    ], include_directories : inc,
      dependencies : [threads_dep, avutil_dep, sdbus_dep, pipewire_dep, gio_test_dep],
      build_by_default : false)
  endif
endif
