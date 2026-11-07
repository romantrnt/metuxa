# Native metuxa control panel. Never replaces the original MetaShare launcher.
gtkmm_dep = dependency('gtkmm-4.0', version : '>=4.10', required : false)
json_glib_dep = dependency('json-glib-1.0', required : false)
if not gtkmm_dep.found() or not json_glib_dep.found()
  message('metuxa control: disabled (gtkmm >=4.10 and json-glib are required)')
  subdir_done()
endif
control_sources = files('control_model.cpp', 'server_process.cpp')
executable('metuxa-control', files('main.cpp', 'main_window.cpp') + control_sources,
  dependencies : [gtkmm_dep, json_glib_dep], install : true)
control_tests = executable('metuxa-control-tests', files('tests/control_tests.cpp') + control_sources,
  include_directories : include_directories('.'), dependencies : [gtkmm_dep, json_glib_dep])
test('metuxa-control', control_tests, timeout : 20)
install_data('dev.metuxa.Control.desktop', install_dir : get_option('datadir') / 'applications')
install_data('../../data/brand/metuxa-icon.svg',
  install_dir : get_option('datadir') / 'icons' / 'hicolor' / 'scalable' / 'apps',
  rename : 'dev.metuxa.Control.svg')

# Explicit isolated UI QA target. Not a default test: requires private Weston.
executable('metuxa-ui-tests', files('tests/ui_tests.cpp', 'main_window.cpp') + control_sources,
  include_directories : include_directories('.'), dependencies : [gtkmm_dep, json_glib_dep])
