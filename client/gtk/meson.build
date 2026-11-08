# Native metuxa control panel. Never replaces the original MetaShare launcher.
gtkmm_dep = dependency('gtkmm-4.0', version : '>=4.10', required : false)
json_glib_dep = dependency('json-glib-1.0', required : false)
if not gtkmm_dep.found() or not json_glib_dep.found()
  message('metuxa control: disabled (gtkmm >=4.10 and json-glib are required)')
  subdir_done()
endif
update_deps = [dependency('libcurl', version : '>=7.85'), dependency('openssl'), dependency('threads')]
update_args = ['-DMETUXA_VERSION="' + meson.project_version() + '"']
control_sources = files('control_model.cpp', 'app_preferences.cpp', 'server_process.cpp', 'update_manager.cpp', 'system_appearance.cpp')
executable('metuxa-control', files('main.cpp', 'main_window.cpp', 'main_preferences.cpp') + control_sources,
  cpp_args : update_args, dependencies : [gtkmm_dep, json_glib_dep] + update_deps, install : true)
control_tests = executable('metuxa-control-tests', files('tests/control_tests.cpp') + control_sources,
  include_directories : include_directories('.'), cpp_args : update_args, dependencies : [gtkmm_dep, json_glib_dep] + update_deps)
test('metuxa-control', control_tests, timeout : 20)
install_data('dev.metuxa.Control.desktop', install_dir : get_option('datadir') / 'applications')
install_data('../../data/branding/metuxa-tux.png',
  install_dir : get_option('datadir') / 'icons' / 'hicolor' / '512x512' / 'apps',
  rename : 'dev.metuxa.Control.png')

# Explicit isolated UI QA target. Not a default test: requires private Weston.
executable('metuxa-ui-tests', files('tests/ui_tests.cpp', 'main_window.cpp', 'main_preferences.cpp') + control_sources,
  include_directories : include_directories('.'), cpp_args : update_args, dependencies : [gtkmm_dep, json_glib_dep] + update_deps)

update_tests = executable('metuxa-update-tests', files('tests/update_tests.cpp', 'update_manager.cpp'),
  include_directories : include_directories('.'), cpp_args : update_args + ['-DMETUXA_UPDATE_TESTING'],
  dependencies : update_deps)
test('metuxa-updates', update_tests, timeout : 30)

# A private schema and memory backend keep appearance tests independent of the
# host's GNOME installation and prevent any changes to desktop preferences.
theme_schema = custom_target('metuxa-theme-test-schema',
  input : 'tests/schemas/org.gnome.desktop.interface.gschema.xml',
  output : 'gschemas.compiled',
  command : [find_program('glib-compile-schemas'), '--strict',
    '--targetdir', meson.current_build_dir(), meson.current_source_dir() / 'tests/schemas'])
appearance_tests = executable('metuxa-appearance-tests',
  files('tests/system_appearance_tests.cpp', 'system_appearance.cpp'),
  include_directories : include_directories('.'), dependencies : dependency('gio-2.0'))
test('metuxa-appearance', appearance_tests, depends : theme_schema, timeout : 20,
  env : ['GSETTINGS_SCHEMA_DIR=' + meson.current_build_dir(), 'GSETTINGS_BACKEND=memory'])
test('metuxa-appearance-no-schema', appearance_tests, args : ['--no-schema'], timeout : 20,
  env : ['GSETTINGS_SCHEMA_DIR=/nonexistent', 'XDG_DATA_DIRS=/nonexistent', 'GSETTINGS_BACKEND=memory'])
