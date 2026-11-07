#include "control_model.hpp"
#include <glib.h>
#include <glib/gstdio.h>
#include <json-glib/json-glib.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <filesystem>
#include <ifaddrs.h>
#include <net/if.h>
#include <arpa/inet.h>
#include <sstream>
#include <unistd.h>

namespace metuxa::control {
void Settings::normalize() {
    if (source != "portal" && source != "window" && source != "mutter" && source != "test") source = "portal";
    if (codec != "hevc" && codec != "h264") codec = "hevc";
    fps = std::clamp(fps, 24, 120);
    bitrate = std::clamp(bitrate, 1000, 80000);
    monitors = std::clamp(monitors, 1, 3);
    width = std::clamp(width, 640, 3840) & ~1;
    height = std::clamp(height, 480, 2160) & ~1;
    port = std::clamp(port, 1024, 65530);
    if (source == "window") { monitors = 1; remote_input = false; }
    if (source == "test") { audio = false; remote_input = false; }
}
std::vector<std::string> Settings::arguments(const std::string& executable) const {
    auto s = *this; s.normalize();
    std::vector<std::string> a{executable, "--status-fd", "3", "--source", s.source,
        "--monitors", std::to_string(s.monitors), "--fps", std::to_string(s.fps),
        "--bitrate", std::to_string(s.bitrate), "--codec", s.codec,
        "--width", std::to_string(s.width), "--height", std::to_string(s.height),
        "--port", std::to_string(s.port), "--audio", s.audio ? "system" : "none"};
    a.emplace_back(s.remote_input ? "--input" : "--no-input");
    if (!s.hardware) a.emplace_back("--no-hw");
    if (!s.discovery) a.emplace_back("--no-discovery");
    return a;
}
std::string Event::get(const std::string& key) const {
    auto i = strings.find(key); return i == strings.end() ? "" : i->second;
}
int Event::number(const std::string& key, int fallback) const {
    auto i = numbers.find(key); return i == numbers.end() ? fallback : i->second;
}
bool parse_event(const std::string& line, Event& event) {
    if (line.empty() || line.size() > 8192) return false;
    JsonParser* parser = json_parser_new();
    bool ok = false;
    if (json_parser_load_from_data(parser, line.data(), line.size(), nullptr)) {
        auto* node = json_parser_get_root(parser);
        if (JSON_NODE_HOLDS_OBJECT(node)) {
            event = {};
            auto* object = json_node_get_object(node);
            static const char* strings[] = {"type", "state", "message", "reason", "fingerprint", "pairing_code", "endpoint", "codec", "encoder"};
            for (const auto* key : strings) {
                auto* member = json_object_get_member(object, key);
                if (member && JSON_NODE_HOLDS_VALUE(member) && json_node_get_value_type(member) == G_TYPE_STRING) {
                    auto* value = json_node_get_string(member);
                    if (value && strlen(value) <= 1024) event.strings[key] = value;
                }
            }
            static const char* numbers[] = {"monitor", "width", "height", "fps", "bitrate", "input_available", "input_active", "audio_active", "port", "clients"};
            for (const auto* key : numbers) {
                auto* member = json_object_get_member(object, key);
                if (!member || !JSON_NODE_HOLDS_VALUE(member)) continue;
                auto type = json_node_get_value_type(member);
                if (type == G_TYPE_BOOLEAN) event.numbers[key] = json_node_get_boolean(member);
                else if (type == G_TYPE_INT64) {
                    auto value = json_node_get_int(member);
                    if (value >= 0 && value <= 1000000) event.numbers[key] = static_cast<int>(value);
                }
            }
            ok = !event.get("type").empty() || !event.get("state").empty();
        }
    }
    g_object_unref(parser);
    return ok;
}
std::string sanitize_log(const std::string& line) {
    std::string lower = line;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
    for (auto* word : {"pairing", "token", "password", "secret", "authorization", "keycode", "keysym", "text_input", "text=", "key=", "button=", "[input]", "input text", "text:", "keyboard event"}) {
        if (lower.find(word) != std::string::npos) return "[Sensitive session detail omitted]";
    }
    std::string result;
    for (std::size_t i = 0; i < line.size() && result.size() < 2048;) {
        // Hide long hexadecimal runs, including an accidentally printed pin/code.
        auto end = i;
        while (end < line.size() && std::isxdigit(static_cast<unsigned char>(line[end]))) ++end;
        if (end - i >= 32) { result += "[redacted]"; i = end; continue; }
        unsigned char c = line[i++];
        if (c == '\t' || c >= 32) result.push_back(c);
    }
    auto* valid = g_utf8_make_valid(result.c_str(), result.size());
    std::string utf8(valid); g_free(valid); return utf8;
}
std::string settings_path() { return std::string(g_get_user_config_dir()) + "/metuxa/control.ini"; }
Settings load_settings(const std::string& path, std::string& warning) {
    Settings settings;
    auto* file = g_key_file_new();
    GError* error = nullptr;
    if (!g_key_file_load_from_file(file, path.c_str(), G_KEY_FILE_NONE, &error)) {
        if (error && !g_error_matches(error, G_FILE_ERROR, G_FILE_ERROR_NOENT)) warning = "Saved settings could not be read. Safe defaults are in use.";
        g_clear_error(&error); g_key_file_unref(file); return settings;
    }
    auto integer = [&](const char* key, int fallback) {
        GError* e = nullptr; int v = g_key_file_get_integer(file, "stream", key, &e);
        if (e) { g_error_free(e); return fallback; } return v;
    };
    auto boolean = [&](const char* key, bool fallback) {
        GError* e = nullptr; bool v = g_key_file_get_boolean(file, "stream", key, &e);
        if (e) { g_error_free(e); return fallback; } return v;
    };
    auto string = [&](const char* key, std::string fallback) {
        auto* v = g_key_file_get_string(file, "stream", key, nullptr);
        if (!v) return fallback;
        std::string value(v); g_free(v); return value;
    };
    settings.source = string("source", settings.source); settings.codec = string("codec", settings.codec);
    settings.fps = integer("fps", settings.fps); settings.bitrate = integer("bitrate", settings.bitrate);
    settings.monitors = integer("monitors", settings.monitors); settings.width = integer("width", settings.width);
    settings.height = integer("height", settings.height); settings.port = integer("port", settings.port);
    settings.audio = boolean("audio", settings.audio); settings.hardware = boolean("hardware", settings.hardware);
    settings.discovery = boolean("discovery", settings.discovery);
    // Remote control is session consent, never silently restored on app launch.
    settings.remote_input = false;
    settings.normalize(); g_key_file_unref(file); return settings;
}
bool save_settings(const std::string& path, Settings s, std::string& error) {
    s.normalize();
    std::filesystem::path parent = std::filesystem::path(path).parent_path();
    if (g_mkdir_with_parents(parent.c_str(), 0700) != 0) { error = "Cannot create the metuxa settings folder."; return false; }
    auto* file = g_key_file_new();
    g_key_file_set_string(file, "stream", "source", s.source.c_str());
    g_key_file_set_string(file, "stream", "codec", s.codec.c_str());
    for (const auto& [key, value] : std::map<const char*, int>{{"fps", s.fps}, {"bitrate", s.bitrate}, {"monitors", s.monitors}, {"width", s.width}, {"height", s.height}, {"port", s.port}})
        g_key_file_set_integer(file, "stream", key, value);
    g_key_file_set_boolean(file, "stream", "audio", s.audio);
    g_key_file_set_boolean(file, "stream", "hardware", s.hardware);
    g_key_file_set_boolean(file, "stream", "discovery", s.discovery);
    gsize size = 0; auto* data = g_key_file_to_data(file, &size, nullptr);
    GError* e = nullptr;
    bool ok = g_file_set_contents_full(path.c_str(), data, size,
        static_cast<GFileSetContentsFlags>(G_FILE_SET_CONTENTS_CONSISTENT | G_FILE_SET_CONTENTS_DURABLE), 0600, &e);
    if (!ok) error = "Cannot save settings: " + std::string(e ? e->message : "unknown error");
    g_clear_error(&e); g_free(data); g_key_file_unref(file); return ok;
}
std::string diagnostic_report(const Settings& s, const std::vector<std::string>& events) {
    std::ostringstream out;
    out << "metuxa control diagnostic report\nNo pairing codes, host addresses, fingerprints, captured content, or raw server logs are included.\n\n"
        << "Source: " << s.source << "\nCodec preference: " << s.codec << "\nRequested fps: " << s.fps
        << "\nBitrate ceiling (kbps): " << s.bitrate << "\nScreens: " << s.monitors
        << "\nVirtual resolution: " << s.width << 'x' << s.height << "\nSystem audio: " << s.audio
        << "\nRemote input requested: " << s.remote_input << "\nHardware encoding preferred: " << s.hardware << "\n\nSession events:\n";
    // Callers only record fixed state labels / numeric, validated stream fields.
    for (const auto& event : events) out << event << '\n';
    return out.str();
}
std::string resolve_server() {
    // Prefer the paired binary in an AppDir or the same build, never old MetaShare.
    std::array<char, 4096> buffer{};
    auto count = readlink("/proc/self/exe", buffer.data(), buffer.size() - 1);
    if (count > 0) {
        std::filesystem::path dir = std::filesystem::path(std::string(buffer.data(), count)).parent_path();
        for (const auto& p : {dir / "metuxa-server", dir / "../../src/streamer/metuxa-server"})
            if (access(p.c_str(), X_OK) == 0) return p.lexically_normal().string();
    }
    auto* path = g_find_program_in_path("metuxa-server");
    if (!path) return {};
    std::string result(path); g_free(path); return result;
}
std::vector<std::string> local_endpoints(int port) {
    std::vector<std::string> addresses;
    ifaddrs* interfaces = nullptr;
    if (getifaddrs(&interfaces) != 0) return addresses;
    for (auto* it = interfaces; it; it = it->ifa_next) {
        if (!it->ifa_addr || it->ifa_addr->sa_family != AF_INET || !(it->ifa_flags & IFF_UP) || (it->ifa_flags & IFF_LOOPBACK)) continue;
        char address[INET_ADDRSTRLEN]{};
        auto* ipv4 = reinterpret_cast<sockaddr_in*>(it->ifa_addr);
        if (inet_ntop(AF_INET, &ipv4->sin_addr, address, sizeof(address))) addresses.emplace_back(std::string(address) + ":" + std::to_string(port));
    }
    freeifaddrs(interfaces);
    std::sort(addresses.begin(), addresses.end()); addresses.erase(std::unique(addresses.begin(), addresses.end()), addresses.end());
    return addresses;
}
} // namespace metuxa::control
