#include "app_preferences.hpp"
#include <glib.h>
#include <glib/gstdio.h>
#include <algorithm>
#include <filesystem>
namespace metuxa::control {
void AppPreferences::normalize() {
    if (theme != "system" && theme != "dark" && theme != "light" && theme != "contrast") theme = "system";
    if (updates != "off" && updates != "notify" && updates != "download") updates = "notify";
    if (check_hours != 1 && check_hours != 6 && check_hours != 24 && check_hours != 168) check_hours = 24;
    text_percent = std::clamp(text_percent, 85, 150);
    window_width = std::clamp(window_width, 620, 2400); window_height = std::clamp(window_height, 620, 1800);
    if (skipped_version.size() > 32 || !std::all_of(skipped_version.begin(), skipped_version.end(), [](unsigned char c) { return (c >= '0' && c <= '9') || c == '.'; })) skipped_version.clear();
}
std::string preferences_path() { return std::string(g_get_user_config_dir()) + "/metuxa/preferences.ini"; }
AppPreferences load_preferences(const std::string& path) {
    AppPreferences p; auto* file = g_key_file_new();
    if (!g_key_file_load_from_file(file, path.c_str(), G_KEY_FILE_NONE, nullptr)) { g_key_file_unref(file); return p; }
    auto string = [&](const char* key, std::string fallback) { auto* v = g_key_file_get_string(file,"app",key,nullptr); if (!v) return fallback; std::string result(v); g_free(v); return result; };
    auto integer = [&](const char* key, int fallback) { GError* e = nullptr; int v = g_key_file_get_integer(file,"app",key,&e); if (e) { g_error_free(e); return fallback; } return v; };
    auto boolean = [&](const char* key, bool fallback) { GError* e = nullptr; bool v = g_key_file_get_boolean(file,"app",key,&e); if (e) { g_error_free(e); return fallback; } return v; };
    p.theme = string("theme",p.theme); p.updates = string("updates",p.updates); p.skipped_version = string("skipped_version","");
    p.check_hours = integer("check_hours",p.check_hours); p.text_percent = integer("text_percent",p.text_percent);
    p.window_width = integer("window_width",p.window_width); p.window_height = integer("window_height",p.window_height);
    p.animations = boolean("animations",true); p.auto_scroll = boolean("auto_scroll",true); p.remember_window = boolean("remember_window",true);
    p.normalize(); g_key_file_unref(file); return p;
}
bool save_preferences(const std::string& path, AppPreferences p, std::string& error) {
    p.normalize(); const auto parent = std::filesystem::path(path).parent_path();
    if (g_mkdir_with_parents(parent.c_str(),0700) != 0) { error = "Cannot create the settings folder."; return false; }
    auto* f = g_key_file_new();
    g_key_file_set_string(f,"app","theme",p.theme.c_str()); g_key_file_set_string(f,"app","updates",p.updates.c_str()); g_key_file_set_string(f,"app","skipped_version",p.skipped_version.c_str());
    g_key_file_set_integer(f,"app","check_hours",p.check_hours); g_key_file_set_integer(f,"app","text_percent",p.text_percent);
    g_key_file_set_integer(f,"app","window_width",p.window_width); g_key_file_set_integer(f,"app","window_height",p.window_height);
    g_key_file_set_boolean(f,"app","animations",p.animations); g_key_file_set_boolean(f,"app","auto_scroll",p.auto_scroll); g_key_file_set_boolean(f,"app","remember_window",p.remember_window);
    gsize size=0; auto* data = g_key_file_to_data(f,&size,nullptr); GError* e=nullptr;
    bool ok = g_file_set_contents_full(path.c_str(),data,size,static_cast<GFileSetContentsFlags>(G_FILE_SET_CONTENTS_CONSISTENT|G_FILE_SET_CONTENTS_DURABLE),0600,&e);
    if (!ok) error = "Cannot save preferences: " + std::string(e ? e->message : "unknown error");
    g_clear_error(&e); g_free(data); g_key_file_unref(f); return ok;
}
}
