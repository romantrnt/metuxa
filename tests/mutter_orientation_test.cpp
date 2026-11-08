// Real compositor regression. Invoke only through run-isolated-mutter.sh.
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <gio/gio.h>
#include "mutter_screencast.hpp"
#include "mutter_virtual_source.hpp"

namespace {
using Modes = std::map<std::string, std::pair<int, int>>;
void require(bool ok, const std::string& message) {
    if (!ok) throw std::runtime_error(message);
}
void require_result(bool ok, const char* operation, const std::string& error) {
    require(ok, std::string(operation) + ": " + error);
}
Modes current_modes() {
    GError* error = nullptr;
    GDBusConnection* bus = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, &error);
    if (!bus) {
        std::string message = error ? error->message : "No private bus";
        g_clear_error(&error);
        throw std::runtime_error(message);
    }
    GVariant* reply = g_dbus_connection_call_sync(bus, "org.gnome.Mutter.DisplayConfig",
        "/org/gnome/Mutter/DisplayConfig", "org.gnome.Mutter.DisplayConfig", "GetCurrentState",
        nullptr, nullptr, G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
    g_object_unref(bus);
    if (!reply) {
        std::string message = error ? error->message : "No display state";
        g_clear_error(&error);
        throw std::runtime_error(message);
    }
    Modes result;
    GVariant* monitors = g_variant_get_child_value(reply, 1);
    for (gsize i = 0; i < g_variant_n_children(monitors); ++i) {
        GVariant* monitor = g_variant_get_child_value(monitors, i);
        GVariant* spec = g_variant_get_child_value(monitor, 0);
        GVariant* connector = g_variant_get_child_value(spec, 0);
        const std::string name = g_variant_get_string(connector, nullptr);
        GVariant* modes = g_variant_get_child_value(monitor, 1);
        for (gsize j = 0; j < g_variant_n_children(modes); ++j) {
            GVariant* mode = g_variant_get_child_value(modes, j);
            GVariant* properties = g_variant_get_child_value(mode, 6);
            gboolean current = false;
            if (g_variant_lookup(properties, "is-current", "b", &current) && current) {
                gint32 width = 0, height = 0;
                g_variant_get_child(mode, 1, "i", &width);
                g_variant_get_child(mode, 2, "i", &height);
                result.emplace(name, std::make_pair(width, height));
            }
            g_variant_unref(properties);
            g_variant_unref(mode);
        }
        g_variant_unref(modes);
        g_variant_unref(connector);
        g_variant_unref(spec);
        g_variant_unref(monitor);
    }
    GVariant* logical = g_variant_get_child_value(reply, 2);
    for (gsize i = 0; i < g_variant_n_children(logical); ++i) {
        GVariant* monitor = g_variant_get_child_value(logical, i);
        guint32 transform = 0;
        g_variant_get_child(monitor, 3, "u", &transform);
        g_variant_unref(monitor);
        require(transform == 0, "Monitor image transform must remain normal");
    }
    g_variant_unref(logical);
    g_variant_unref(monitors);
    g_variant_unref(reply);
    return result;
}
void check_modes(const Modes& original, const std::string& owned, int width, int height) {
    const auto actual = current_modes();
    require(actual.size() == original.size() + 1, "Unexpected monitor count");
    for (const auto& [name, mode] : original) {
        require(actual.contains(name) && actual.at(name) == mode, "Unrelated monitor changed");
    }
    require(actual.contains(owned) && actual.at(owned) == std::make_pair(width, height),
            "Mutter's actual mode does not match the requested desktop dimensions");
}
}

int main() {
    if (!std::getenv("METUXA_ISOLATED_MUTTER")) {
        std::cerr << "Use tests/run-isolated-mutter.sh; do not run against your desktop.\n";
        return 2;
    }
    try {
        const auto original = current_modes();
        require(!original.empty(), "Private compositor baseline monitor missing");
        metashare::MutterScreenCastSession session;
        std::string error;
        require_result(session.open(error), "Open", error);
        require_result(session.add_virtual_monitor(640, 360, error) == 0, "Create virtual", error);
        require_result(session.start(error, 5), "Start session", error);
        const auto node = session.monitors().at(0).node_id;
        const auto path = session.monitors().at(0).stream_path;
        metashare::MutterVirtualSource source(session, 0, 30);
        require_result(source.start(error), "Start source", error);
        require_result(source.resize_desktop(640, 360, error), "Initial mode", error);
        const auto initial = current_modes();
        std::string owned;
        for (const auto& [name, mode] : initial) if (!original.contains(name)) owned = name;
        require(!owned.empty(), "Owned virtual monitor missing");
        check_modes(original, owned, 640, 360);
        for (int cycle = 0; cycle < 3; ++cycle) {
            for (const auto& [width, height] : {std::pair{360, 640}, std::pair{640, 360}}) {
                error.clear();
                require_result(source.resize_desktop(width, height, error), "Resize", error);
                const auto format = source.format();
                require(format.width == width && format.height == height, "Capture format differs from desktop mode");
                require(session.monitors().at(0).node_id == node && session.monitors().at(0).stream_path == path,
                        "Resize replaced the owned stream/session");
                check_modes(original, owned, width, height);
                std::cout << "PASS: real Mutter mode and PipeWire frame " << width << 'x' << height << '\n';
            }
        }
        source.stop();
        session.close();
        for (int i = 0; i < 50 && current_modes() != original; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        require(current_modes() == original, "Owned monitor survived session cleanup");
        session.close();
        std::cout << "PASS: original monitor unchanged, owned monitor removed, repeated close safe\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
