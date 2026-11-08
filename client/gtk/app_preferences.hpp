#pragma once
#include <string>
namespace metuxa::control {
struct AppPreferences {
    std::string theme = "system";
    std::string updates = "notify";
    std::string skipped_version;
    int check_hours = 24;
    int text_percent = 100;
    int window_width = 780, window_height = 850;
    bool animations = true, auto_scroll = true, remember_window = true;
    void normalize();
};
std::string preferences_path();
AppPreferences load_preferences(const std::string& path);
bool save_preferences(const std::string& path, AppPreferences preferences, std::string& error);
}
