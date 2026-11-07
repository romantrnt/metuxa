#include "main_window.hpp"
#include <array>
#include <csignal>
#include <iostream>
#include <unistd.h>
namespace {
bool complete = false;
volatile sig_atomic_t mock_input = 1;
void check(bool value, const char* message) {
    if (!value) { std::cerr << "FAIL UI: " << message << '\n'; std::exit(1); }
}
std::string self_path() {
    std::array<char, 4096> data{}; auto n = readlink("/proc/self/exe", data.data(), data.size() - 1);
    check(n > 0, "read test binary path"); return {data.data(), static_cast<std::size_t>(n)};
}
void send(const std::string& line) { auto text = line + '\n'; write(3, text.data(), text.size()); }
int mock_server() {
    signal(SIGUSR1, [](int) { mock_input = 0; }); signal(SIGUSR2, [](int) { mock_input = 1; });
    send(R"({"state":"starting"})");
    send("{\"type\":\"security\",\"fingerprint\":\"" + std::string(64, 'a') + "\",\"pairing_code\":\"" + std::string(32, 'b') + "\"}");
    send(R"({"state":"requesting_access"})"); usleep(50000);
    send(R"({"type":"stream","monitor":0,"codec":"hevc","encoder":"test_encoder","width":1920,"height":1080,"fps":72})");
    send(R"({"state":"listening"})");
    int previous = -1;
    for (;;) {
        if (previous != mock_input) {
            send(std::string("{\"type\":\"capability\",\"monitor\":0,\"input_available\":true,\"input_active\":") + (mock_input ? "true" : "false") + ",\"audio_active\":true}");
            previous = mock_input;
        }
        usleep(10000);
    }
}
GtkWidget* find_button(GtkWidget* widget, const char* label) {
    if (GTK_IS_BUTTON(widget)) {
        const char* value = gtk_button_get_label(GTK_BUTTON(widget));
        if (value && std::string(value) == label) return widget;
    }
    for (auto* child = gtk_widget_get_first_child(widget); child; child = gtk_widget_get_next_sibling(child)) if (auto* button = find_button(child, label)) return button;
    return nullptr;
}
bool click_dialog(const char* text) {
    auto* windows = gtk_window_get_toplevels();
    for (guint i = 0; i < g_list_model_get_n_items(windows); ++i) {
        auto* window = GTK_WIDGET(g_list_model_get_item(windows, i));
        auto* button = find_button(window, text);
        if (button) g_signal_emit_by_name(button, "clicked");
        g_object_unref(window);
        if (button) return true;
    }
    return false;
}
}
struct ControlUiTest {
    MainWindow& w;
    bool finished() const { return step == 9 && !w.server_.running() && w.code_.empty() && w.pin_.empty(); }
    int step = 0, ticks = 0;
    bool snapshot(const char* name) {
        const char* directory = g_getenv("METUXA_TEST_SCREENSHOTS");
        if (!directory) return true;
        auto* paintable = gtk_widget_paintable_new(GTK_WIDGET(w.gobj()));
        auto* snapshot = gtk_snapshot_new();
        gdk_paintable_snapshot(paintable, GDK_SNAPSHOT(snapshot), w.get_width(), w.get_height());
        auto* node = gtk_snapshot_free_to_node(snapshot);
        if (!node) { g_object_unref(paintable); return false; }
        auto* renderer = gtk_native_get_renderer(GTK_NATIVE(w.gobj()));
        auto* texture = gsk_renderer_render_texture(renderer, node, nullptr);
        bool result = texture && gdk_texture_save_to_png(texture, (std::string(directory) + "/" + name + ".png").c_str());
        if (texture) g_object_unref(texture);
        gsk_render_node_unref(node); g_object_unref(paintable); return result;
    }
    bool tick() {
        check(++ticks < 100, "UI test deadline");
        switch (step) {
        case 0:
            check(!w.server_.running() && !w.settings_.remote_input && w.settings_.fps == 72, "initial view-only defaults");
            check(!w.copy_code_.get_sensitive() && !w.live_input_box_.get_visible(), "idle controls");
            check(snapshot("stream"), "initial screenshot");
            w.profile_.set_selected(2);
            check(w.fps_.get_value_as_int() == 90 && w.bitrate_.get_value_as_int() == 12000, "quality preset");
            w.source_.set_selected(1); w.monitors_.set_value(3); // Model still enforces single-window.
            check(w.read_widgets().monitors == 1 && !w.input_.get_sensitive(), "window source view-only and single-screen");
            w.source_.set_selected(3); check(!w.audio_.get_sensitive() && !w.input_.get_active(), "test source permissions");
            w.settings_ = {}; w.settings_.remote_input = true; w.load_widgets();
            { std::string error; check(w.server_.start({self_path(), "--mock-server"}, error), "start synthetic UI server"); }
            w.update_actions(); ++step; break;
        case 1:
            if (w.pin_.empty() || !w.input_available_[0]) break;
            check(w.status_badge_.get_text() == "Listening", "listening is not connected");
            check(!w.settings_box_.get_sensitive() && w.live_input_.get_sensitive(), "running setting lock / live control");
            check(!w.pairing_code_.get_visibility() && w.copy_code_.get_sensitive(), "pairing code hidden, explicit copy available");
            // Screenshots use documentation-only addresses and synthetic credentials.
            w.endpoints_.set_text("192.0.2.10:7878"); w.stack_.set_visible_child("pair"); ++step; break;
        case 2:
            check(snapshot("pairing"), "pairing screenshot");
            w.live_input_.set_active(false); ++step; break;
        case 3:
            if (w.input_active_[0]) break;
            check(!w.live_input_.get_active(), "live input block acknowledged");
            w.live_input_.set_active(true); ++step; break;
        case 4:
            if (!w.input_active_[0]) break;
            w.on_event(R"({"state":"connected"})");
            check(w.status_badge_.get_text() == "Connected", "authenticated connected status");
            w.stack_.set_visible_child("activity"); ++step; break;
        case 5:
            check(snapshot("activity"), "activity screenshot");
            check(w.close_requested(), "active close is intercepted"); ++step; break;
        case 6:
            if (!click_dialog("Keep sharing")) break;
            ++step; break;
        case 7:
            if (w.confirming_close_) break;
            check(w.server_.running() && !w.closing_, "cancel close keeps current session");
            check(w.close_requested(), "second close is intercepted"); ++step; break;
        case 8:
            if (!click_dialog("Stop and close")) break;
            ++step; break;
        case 9:
            if (w.server_.running()) break;
            check(w.code_.empty() && w.pin_.empty() && !w.copy_code_.get_sensitive(), "close invalidates pairing UI");
            complete = true;
            std::cout << "PASS: isolated GUI startup, sources, presets, capability states, private pairing, live input gates, close cancellation, close shutdown\n";
            if (auto app = w.get_application()) app->quit();
            return false;
        }
        return true;
    }
};
int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "--mock-server") return mock_server();
    const auto* display = g_getenv("WAYLAND_DISPLAY"); const auto* config = g_getenv("XDG_CONFIG_HOME");
    check(g_getenv("METUXA_TEST_ISOLATED") && display && std::string(display).rfind("metuxa-test-", 0) == 0 && config && std::string(config).rfind("/tmp/metuxa-ui-", 0) == 0, "requires isolated Weston display and temporary config");
    auto app = Gtk::Application::create("dev.metuxa.Control.Test", Gio::Application::Flags::NON_UNIQUE);
    std::unique_ptr<MainWindow> window; std::unique_ptr<ControlUiTest> test;
    app->signal_activate().connect([&] {
        window = std::make_unique<MainWindow>();
        if (g_getenv("METUXA_TEST_COMPACT")) window->set_default_size(620, 700);
        if (g_getenv("METUXA_TEST_DARK")) Gtk::Settings::get_default()->property_gtk_application_prefer_dark_theme() = true;
        app->add_window(*window); window->present();
        test = std::make_unique<ControlUiTest>(ControlUiTest{*window});
        Glib::signal_timeout().connect([&] { return test->tick(); }, 200);
    });
    int result = app->run(1, argv);
    // Window hiding can end GApplication before the next timer; inspect directly.
    check(complete || (test && test->finished()), "close completed without orphaned server or credentials");
    if (!complete) std::cout << "PASS: isolated GUI startup, sources, presets, capability states, private pairing, live input gates, close cancellation, close shutdown\n";
    return result;
}
