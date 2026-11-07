// metuxa control panel. Original MetaShare components retain their MIT license.
#pragma once
#include <map>
#include <string>
#include <vector>

namespace metuxa::control {
struct Settings {
    std::string source = "portal";
    std::string codec = "hevc";
    int fps = 72;
    int bitrate = 8000;
    int monitors = 1;
    int width = 1920;
    int height = 1080;
    int port = 7878;
    bool audio = true;
    bool remote_input = false;
    bool hardware = true;
    bool discovery = true;
    void normalize();
    std::vector<std::string> arguments(const std::string& executable) const;
    bool operator==(const Settings&) const = default;
};
struct Event {
    std::map<std::string, std::string> strings;
    std::map<std::string, int> numbers;
    std::string get(const std::string& key) const;
    int number(const std::string& key, int fallback = 0) const;
};
bool parse_event(const std::string& line, Event& event);
std::string sanitize_log(const std::string& line);
std::string settings_path();
Settings load_settings(const std::string& path, std::string& warning);
bool save_settings(const std::string& path, Settings settings, std::string& error);
std::string diagnostic_report(const Settings& settings, const std::vector<std::string>& events);
std::string resolve_server();
std::vector<std::string> local_endpoints(int port);
} // namespace metuxa::control
