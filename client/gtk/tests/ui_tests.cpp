#include "main_window.hpp"
#include "fake_settings_portal.hpp"
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
bool escape_window(Gtk::Window& window) {
    auto* controllers = gtk_widget_observe_controllers(GTK_WIDGET(window.gobj()));
    bool handled = false;
    for (guint i = 0; i < g_list_model_get_n_items(controllers); ++i) {
        auto* controller = g_list_model_get_item(controllers, i);
        if (GTK_IS_EVENT_CONTROLLER_KEY(controller)) {
            gboolean result = false;
            g_signal_emit_by_name(controller, "key-pressed", GDK_KEY_Escape, 0, static_cast<GdkModifierType>(0), &result);
            handled = handled || result;
        }
        g_object_unref(controller);
    }
    g_object_unref(controllers); return handled;
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
    FakeSettingsPortal& portal;
    int theme_step = 0;
    bool dark() const {
        auto* settings = Gtk::Settings::get_default()->gobj();
        auto* spec = g_object_class_find_property(G_OBJECT_GET_CLASS(settings), "gtk-interface-color-scheme");
        if (spec && G_IS_PARAM_SPEC_ENUM(spec)) {
            int current = 0; g_object_get(settings, "gtk-interface-color-scheme", &current, nullptr);
            auto* values = G_ENUM_CLASS(g_type_class_ref(spec->value_type));
            auto* value = g_enum_get_value_by_nick(values, "dark");
            const bool result = value && current == value->value;
            g_type_class_unref(values); return result;
        }
        return Gtk::Settings::get_default()->property_gtk_application_prefer_dark_theme();
    }
    bool high_contrast() const {
        auto* settings = Gtk::Settings::get_default()->gobj();
        auto* spec = g_object_class_find_property(G_OBJECT_GET_CLASS(settings), "gtk-interface-contrast");
        if (spec && G_IS_PARAM_SPEC_ENUM(spec)) {
            int current = 0; g_object_get(settings, "gtk-interface-contrast", &current, nullptr);
            auto* values = G_ENUM_CLASS(g_type_class_ref(spec->value_type));
            auto* value = g_enum_get_value_by_nick(values, "more");
            const bool result = value && current == value->value;
            g_type_class_unref(values); return result;
        }
        return Gtk::Settings::get_default()->property_gtk_theme_name() == "HighContrast";
    }
    bool check_theme() {
        auto settings = Gtk::Settings::get_default();
        switch (theme_step) {
        case 0:
            if (w.system_appearance_->dark() != true) return false;
            check(w.preferences_.theme == "system" && dark(), "System reads dark portal preference at startup");
            check(snapshot("system-dark"), "System dark screenshot");
            portal.change(2); ++theme_step; return false;
        case 1:
            if (w.system_appearance_->dark() != false) return false;
            check(!dark(), "System changes to light without restarting");
            check(snapshot("system-light"), "System light screenshot");
            w.theme_.set_selected(1); check(dark(), "explicit Dark");
            w.theme_.set_selected(0); check(!dark(), "Dark to System uses current light desktop");
            w.theme_.set_selected(3);
            check(settings->property_gtk_theme_name() == "HighContrast", "explicit contrast");
            portal.change(1); ++theme_step; return false;
        case 2:
            if (w.system_appearance_->dark() != true) return false;
            check(settings->property_gtk_theme_name() == "HighContrast", "system change preserves explicit contrast");
            w.theme_.set_selected(0);
            check(dark() && settings->property_gtk_theme_name() != "HighContrast" && !high_contrast(), "contrast to System restores current desktop");
            w.theme_.set_selected(2); check(!dark(), "explicit Light overrides dark desktop");
            portal.change(2); ++theme_step; return false;
        case 3:
            if (w.system_appearance_->dark() != false) return false;
            portal.change(1); ++theme_step; return false;
        case 4:
            if (w.system_appearance_->dark() != true) return false;
            check(!dark(), "Light survives live desktop changes");
            w.theme_.set_selected(0); check(dark(), "Light to System uses latest dark desktop");
            w.open_preferences(); ++theme_step; return false;
        case 5:
            check(snapshot("settings-system-dark", &w.preferences_window_), "System settings screenshot");
            w.preferences_window_.close();
            w.stack_.set_visible_child("stream");
            w.theme_.set_selected(1); portal.change(0); ++theme_step; return false;
        case 6:
            if (w.system_appearance_->dark().has_value()) return false;
            check(dark(), "explicit Dark survives no system preference");
            w.theme_.set_selected(0);
            check(!dark(), "no system preference resets native GTK default instead of retaining our Dark override");
            portal.change(1); ++theme_step; return false;
        case 7:
            if (w.system_appearance_->dark() != true) return false;
            check(dark(), "System resumes after no preference"); ++theme_step; return false;
        default: return true;
        }
    }
    bool finished() const { return step == 9 && !w.server_.running() && w.code_.empty() && w.pin_.empty(); }
    int step = 0, ticks = 0;
    bool snapshot(const char* name, Gtk::Widget* target = nullptr) {
        auto& window = target ? *target : static_cast<Gtk::Widget&>(w);
        const char* directory = g_getenv("METUXA_TEST_SCREENSHOTS");
        if (!directory) return true;
        auto* paintable = gtk_widget_paintable_new(GTK_WIDGET(window.gobj()));
        auto* snapshot = gtk_snapshot_new();
        gdk_paintable_snapshot(paintable, GDK_SNAPSHOT(snapshot), window.get_width(), window.get_height());
        auto* node = gtk_snapshot_free_to_node(snapshot);
        if (!node) { g_object_unref(paintable); return false; }
        auto* renderer = gtk_native_get_renderer(gtk_widget_get_native(window.gobj()));
        auto* texture = gsk_renderer_render_texture(renderer, node, nullptr);
        bool result = texture && gdk_texture_save_to_png(texture, (std::string(directory) + "/" + name + ".png").c_str());
        if (texture) g_object_unref(texture);
        gsk_render_node_unref(node); g_object_unref(paintable); return result;
    }
    int navigation_step = 0;
    bool check_navigation() {
        switch (navigation_step++) {
        case 0:
            check(w.get_title() == "metuxa 🐧", "stable visible application title");
            check(!w.stack_.get_child_by_name("preferences"), "Settings is not a navigation tab");
            { auto* pages = gtk_stack_get_pages(w.stack_.gobj());
              check(g_list_model_get_n_items(G_LIST_MODEL(pages)) == 3, "only Stream, Pair headset and Activity tabs");
              g_object_unref(pages); }
            w.menu_button_.popup(); return false;
        case 1:
            check(w.menu_popover_.get_visible(), "overflow opens");
            check(snapshot("main-menu", &w.menu_popover_), "overflow screenshot");
            g_signal_emit_by_name(w.preferences_button_.gobj(), "clicked"); return false;
        case 2:
            check(w.preferences_window_.get_visible() && w.stack_.get_visible_child_name() == "stream", "Settings opens separately and preserves tab");
            w.open_preferences(); check(w.preferences_window_.get_visible(), "repeated Settings reuses its window");
            w.text_scale_.set_value(150); return false;
        case 3:
            check(snapshot("settings-large-text", &w.preferences_window_), "150 percent Settings screenshot");
            w.text_scale_.set_value(100); w.preferences_window_.close();
            check(!w.preferences_window_.get_visible(), "Settings close hides it");
            w.menu_button_.popup(); g_signal_emit_by_name(w.about_button_.gobj(), "clicked"); return false;
        case 4:
            check(w.about_window_.get_visible(), "About opens from overflow");
            check(snapshot("about", &w.about_window_), "About screenshot");
            w.open_about(); check(escape_window(w.about_window_), "Escape handled in About");
            check(!w.about_window_.get_visible() && w.stack_.get_visible_child_name() == "stream", "About dismissal preserves navigation");
            w.stack_.set_visible_child("pair"); w.open_preferences(); return false;
        case 5:
            check(escape_window(w.preferences_window_), "Escape handled in Settings");
            check(!w.preferences_window_.get_visible(), "Escape dismisses Settings");
            check(w.stack_.get_visible_child_name() == "pair", "Settings returns to the previous tab");
            w.stack_.set_visible_child("stream");
            w.menu_button_.popup(); w.menu_popover_.popdown(); return false;
        case 6:
            check(!w.menu_popover_.get_visible(), "overflow dismisses without navigation"); return true;
        default: return true;
        }
    }
    bool tick() {
        check(++ticks < 100, "UI test deadline");
        if (!check_theme()) return true;
        if (!check_navigation()) return true;
        switch (step) {
        case 0:
            check(!w.server_.running() && !w.settings_.remote_input && w.settings_.fps == 72, "initial view-only defaults");
            check(!w.copy_code_.get_sensitive() && !w.live_input_box_.get_visible(), "idle controls");
            check(snapshot("stream"), "initial screenshot");
            w.theme_.set_selected(1); w.text_scale_.set_value(125); w.auto_scroll_.set_active(false);
            check(w.preferences_.theme == "dark" && w.preferences_.text_percent == 125 && !w.preferences_.auto_scroll, "live preferences");
            w.theme_.set_selected(2); check(!dark(), "light theme");
            w.theme_.set_selected(0); w.text_scale_.set_value(100); w.auto_scroll_.set_active(true);
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
            w.on_event("{\"type\":\"pairing_request\",\"id\":\"" + std::string(32, 'c') + "\",\"code\":\"12345 67890\",\"peer\":\"192.0.2.20\",\"expires_seconds\":60}");
            check(w.pair_request_box_.get_visible() && !w.pair_accept_.get_sensitive(), "approval requires explicit code comparison");
            w.pair_compared_.set_active(true); check(w.pair_accept_.get_sensitive(), "comparison enables exact request approval");
            w.on_event("{\"type\":\"pairing_closed\",\"id\":\"" + std::string(32, 'd') + "\",\"reason\":\"approved\"}");
            check(!w.pairing_id_.empty(), "stale pairing event ignored");
            w.on_event("{\"type\":\"pairing_closed\",\"id\":\"" + std::string(32, 'c') + "\",\"reason\":\"rejected\"}");
            check(w.pairing_id_.empty() && !w.pair_request_box_.get_visible(), "pairing rejection clears controls");
            w.live_input_.set_active(false); ++step; break;
        case 3:
            if (w.input_active_[0]) break;
            check(!w.live_input_.get_active(), "live input block acknowledged");
            w.live_input_.set_active(true); ++step; break;
        case 4:
            if (!w.input_active_[0]) break;
            w.on_event(R"({"state":"connected"})");
            check(w.status_badge_.get_text() == "Connected", "authenticated connected status");
            w.open_preferences(); ++step; break;
        case 5:
            check(snapshot("settings", &w.preferences_window_), "settings screenshot");
            w.preferences_window_.close();
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
            std::cout << "PASS: live System theme, explicit overrides, isolated GUI startup, sources, presets, capability states, private pairing, live input gates, close cancellation, close shutdown\n";
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
    FakeSettingsPortal portal;
    auto app = Gtk::Application::create("dev.metuxa.Control.Test", Gio::Application::Flags::NON_UNIQUE);
    std::unique_ptr<MainWindow> window; std::unique_ptr<ControlUiTest> test;
    app->signal_activate().connect([&] {
        window = std::make_unique<MainWindow>();
        if (g_getenv("METUXA_TEST_COMPACT")) window->set_default_size(620, 700);
        app->add_window(*window); window->present();
        test = std::make_unique<ControlUiTest>(ControlUiTest{*window, portal});
        Glib::signal_timeout().connect([&] { return test->tick(); }, 200);
    });
    int result = app->run(1, argv);
    // Window hiding can end GApplication before the next timer; inspect directly.
    check(complete || (test && test->finished()), "close completed without orphaned server or credentials");
    if (!complete) std::cout << "PASS: live System theme, explicit overrides, isolated GUI startup, sources, presets, capability states, private pairing, live input gates, close cancellation, close shutdown\n";
    return result;
}
