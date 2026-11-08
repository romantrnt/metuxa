#include "system_appearance.hpp"
#include "fake_settings_portal.hpp"
#include <chrono>
#include <iostream>
#include <thread>

using namespace metuxa::control;
namespace {
void check(bool value, const char* message) {
    if (!value) { std::cerr << "FAIL appearance: " << message << '\n'; std::exit(1); }
}
void pump() {
    while (g_main_context_iteration(nullptr, false)) {}
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
}
void until(const std::function<bool()>& done) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!done() && std::chrono::steady_clock::now() < deadline) pump();
    check(done(), "asynchronous appearance update timed out");
}
void settle() { for (unsigned i = 0; i < 50; ++i) pump(); }
void parse(GVariant* input, std::optional<bool> expected, const char* message) {
    g_variant_ref_sink(input);
    check(portal_color_scheme(input) == expected, message);
    g_variant_unref(input);
}
}
int main(int argc, char** argv) {
    const bool no_schema = argc > 1 && std::string(argv[1]) == "--no-schema";
    g_test_init(&argc, &argv, nullptr);
    g_setenv("GSETTINGS_BACKEND", "memory", true);
    parse(g_variant_new_uint32(1), true, "raw dark");
    parse(g_variant_new("(v)", g_variant_new_uint32(2)), false, "ReadOne light");
    parse(g_variant_new("(v)", g_variant_new_variant(g_variant_new_uint32(1))), true, "legacy Read double variant");
    parse(g_variant_new_uint32(0), {}, "no preference is not forced light");
    parse(g_variant_new_uint32(42), {}, "unknown scheme is not dark");
    parse(g_variant_new_string("1"), {}, "wrong type rejected");
    parse(g_variant_new_int32(1), {}, "signed integer rejected");
    parse(g_variant_new("(uu)", 1u, 2u), {}, "malformed tuple rejected");
    check(!portal_color_scheme(nullptr).has_value(), "missing value");
    check(gnome_color_scheme("prefer-dark") == true && gnome_color_scheme("prefer-light") == false &&
          !gnome_color_scheme("default").has_value() && !gnome_color_scheme(nullptr).has_value(), "GNOME values");

    // GTestDBus has no service directories unless explicitly added. No real
    // desktop, portal, network, capture or input service can be activated here.
    auto* bus = g_test_dbus_new(G_TEST_DBUS_NONE);
    g_test_dbus_up(bus);
    if (no_schema) {
        auto* source = g_settings_schema_source_get_default();
        auto* schema = source ? g_settings_schema_source_lookup(source, "org.gnome.desktop.interface", true) : nullptr;
        check(!schema, "test excludes GNOME schema");
        {
            SystemAppearance appearance([] {});
            check(!appearance.dark().has_value(), "missing GNOME schema leaves GTK fallback");
            FakeSettingsPortal portal;
            until([&] { return appearance.dark() == true; });
            portal.change(0);
            until([&] { return !appearance.dark().has_value(); });
        }
        settle();
        g_test_dbus_down(bus); g_object_unref(bus);
        std::cout << "PASS: portal appearance without installed GNOME schemas\n";
        return 0;
    }
    auto* fallback = g_settings_new("org.gnome.desktop.interface");
    g_settings_set_string(fallback, "color-scheme", "prefer-light");
    {
        unsigned changes = 0;
        SystemAppearance appearance([&] { ++changes; });
        check(appearance.dark() == false, "GNOME fallback before portal is available");
        g_settings_set_string(fallback, "color-scheme", "prefer-dark");
        until([&] { return appearance.dark() == true; });
        g_settings_set_string(fallback, "color-scheme", "default");
        until([&] { return !appearance.dark().has_value(); });
        check(changes == 2, "GNOME changes observed");
        {
            FakeSettingsPortal portal;
            until([&] { return appearance.dark() == true; });
            check(portal.reads == 1 && portal.legacy_reads == 0, "reads actual portal path with ReadOne");
            portal.change(2);
            until([&] { return appearance.dark() == false; });
            g_settings_set_string(fallback, "color-scheme", "prefer-dark");
            settle();
            check(appearance.dark() == false, "portal preference wins over GNOME fallback");
            portal.change(1, "org.example.Unrelated"); settle();
            portal.change(1, "org.freedesktop.appearance", "accent-color"); settle();
            check(appearance.dark() == false, "unrelated signals ignored");
            portal.change(0);
            until([&] { return appearance.dark() == true; });
            g_settings_set_string(fallback, "color-scheme", "default");
            until([&] { return !appearance.dark().has_value(); });
            portal.change(99); settle();
            check(!appearance.dark().has_value(), "unknown portal value leaves native fallback");
            portal.change(1);
            until([&] { return appearance.dark() == true; });
            portal.release();
            until([&] { return !appearance.dark().has_value(); });
            portal.legacy = true; portal.scheme = 2; portal.acquire();
            until([&] { return appearance.dark() == false; });
            check(portal.legacy_reads == 1, "portal restart uses legacy Read fallback");
            portal.release();
            until([&] { return !appearance.dark().has_value(); });
            portal.legacy = false; portal.hold_read = true; portal.acquire();
            until([&] { return portal.pending != nullptr; });
            portal.change(1);
            until([&] { return appearance.dark() == true; });
            portal.finish_read(2); settle();
            check(appearance.dark() == true, "late Read cannot undo newer SettingChanged");
        }
        until([&] { return !appearance.dark().has_value(); });
    }
    {
        FakeSettingsPortal portal;
        portal.hold_read = true;
        unsigned changes = 0;
        {
            SystemAppearance appearance([&] { ++changes; });
            until([&] { return portal.pending != nullptr; });
        }
        portal.finish_read(1); settle();
        check(changes == 0, "destroyed observer ignores pending read completion");
        { SystemAppearance immediately_destroyed([] { check(false, "callback after destruction"); }); }
        settle();
    }
    g_object_unref(fallback);
    g_test_dbus_down(bus);
    g_object_unref(bus);
    std::cout << "PASS: Settings portal reads, variants, changes, restart, stale replies, GNOME fallback and teardown\n";
}
