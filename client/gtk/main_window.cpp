#include "main_window.hpp"
#include <glib.h>
#include <algorithm>
#include <chrono>
#include <iomanip>
#include <sstream>
#include <sys/wait.h>

using namespace metuxa::control;
namespace {
constexpr const char* sources[] = {"portal", "window", "mutter", "test"};
const char* css = R"css(
  .metuxa-brand { font-size: 24px; font-weight: 800; letter-spacing: -1px; }
  .session-panel { background-color: alpha(@theme_fg_color, 0.045); border-radius: 16px; padding: 22px; }
  .session-title { font-size: 23px; font-weight: 700; }
  .session-detail { color: alpha(@theme_fg_color, 0.72); }
  .section-title { font-size: 15px; font-weight: 700; }
  .setting-group { border: 1px solid alpha(@theme_fg_color, 0.12); border-radius: 12px; }
  .setting-row { padding: 12px 16px; }
  .setting-detail { font-size: 12px; color: alpha(@theme_fg_color, 0.65); }
  .status-badge { border-radius: 8px; padding: 5px 10px; font-size: 12px; font-weight: 700; }
  .idle { background: alpha(@theme_fg_color, 0.08); }
  .busy { background: alpha(#d99f28, 0.18); color: @theme_fg_color; }
  .ready { background: alpha(#39996b, 0.18); color: @theme_fg_color; }
  .error { background: alpha(#df5862, 0.18); color: @theme_fg_color; }
  .primary-action { padding: 12px 24px; border-radius: 10px; font-weight: 700; }
  .pair-number { font-family: monospace; font-size: 14px; }
  .notice { border-radius: 9px; padding: 10px 14px; background: alpha(#7a5ce6, 0.1); }
  .log-view { font-family: monospace; font-size: 12px; }
  .permission-note { border-left: 3px solid #7a5ce6; padding: 6px 12px; }
)css";
void spin(Gtk::SpinButton& widget, int low, int high, int step, int digits = 5) {
    widget.set_range(low, high); widget.set_increments(step, step * 5);
    widget.set_numeric(true); widget.set_width_chars(digits);
}
bool hex_string(const std::string& value, std::size_t size) {
    return value.size() == size && std::all_of(value.begin(), value.end(), [](unsigned char c) { return std::isxdigit(c); });
}
std::string timestamp() {
    auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    std::tm tm{}; localtime_r(&now, &tm);
    std::ostringstream out; out << std::put_time(&tm, "%H:%M:%S"); return out.str();
}
}
MainWindow::MainWindow() {
    std::string warning;
    settings_ = load_settings(settings_path(), warning);
    dialogs_cancel_ = Gio::Cancellable::create();
    build_ui(); load_widgets();
    if (!warning.empty()) append_log(warning);
    server_.on_log = [this](const auto& line) { append_log(line); };
    server_.on_event = [this](const auto& line) { on_event(line); };
    server_.on_exit = [this](int status, bool requested) { on_exit(status, requested); };
    signal_close_request().connect(sigc::mem_fun(*this, &MainWindow::close_requested), false);
    set_status("Your desktop, in reach.", "Start a private session, then pair metuxa on your headset.", "idle");
    clear_security(); update_actions();
}
MainWindow::~MainWindow() {
    log_timer_.disconnect(); save_timer_.disconnect(); startup_timer_.disconnect(); dialogs_cancel_->cancel();
    // The supervisor is declared before widgets and will outlive them. Remove
    // all widget callbacks now, then its destructor stops and reaps the child.
    server_.on_log = {}; server_.on_event = {}; server_.on_exit = {};
}
Gtk::Label& MainWindow::label(const std::string& text, const std::string& style) {
    auto* l = Gtk::make_managed<Gtk::Label>(text);
    l->set_xalign(0); l->set_wrap(true); l->set_hexpand(true);
    if (!style.empty()) l->add_css_class(style);
    return *l;
}
Gtk::Box& MainWindow::section(const std::string& title) {
    auto* box = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 8);
    box->append(label(title, "section-title")); return *box;
}
Gtk::Widget& MainWindow::row(const std::string& title, const std::string& detail, Gtk::Widget& control) {
    auto* box = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 18);
    box->add_css_class("setting-row");
    auto* text = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 4);
    text->set_hexpand(true); text->append(label(title));
    if (!detail.empty()) text->append(label(detail, "setting-detail"));
    control.set_valign(Gtk::Align::CENTER); control.set_halign(Gtk::Align::END);
    box->append(*text); box->append(control); return *box;
}
void MainWindow::build_ui() {
    set_title("metuxa"); set_default_size(780, 850); set_size_request(620, 620);
    set_icon_name("dev.metuxa.Control");
    auto provider = Gtk::CssProvider::create(); provider->load_from_data(css);
    Gtk::StyleProvider::add_provider_for_display(Gdk::Display::get_default(), provider, GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    auto* brand = Gtk::make_managed<Gtk::Label>("metuxa"); brand->add_css_class("metuxa-brand");
    header_.pack_start(*brand); header_.set_show_title_buttons(true);
    auto* context = Gtk::make_managed<Gtk::Label>("Server control"); context->add_css_class("dim-label"); header_.set_title_widget(*context);
    header_.pack_end(diagnostics_button_); diagnostics_button_.add_css_class("flat");
    diagnostics_button_.signal_clicked().connect(sigc::mem_fun(*this, &MainWindow::save_diagnostics));
    set_titlebar(header_);
    auto* root = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 18);
    root->set_margin(24);
    auto* hero = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 12); hero->add_css_class("session-panel");
    auto* top = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 12);
    auto* titles = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 6); titles->set_hexpand(true);
    status_title_.set_xalign(0); status_title_.set_wrap(true); status_title_.add_css_class("session-title");
    status_detail_.set_xalign(0); status_detail_.set_wrap(true); status_detail_.set_max_width_chars(54); status_detail_.add_css_class("session-detail");
    titles->append(status_title_); titles->append(status_detail_); top->append(*titles);
    start_button_.add_css_class("primary-action"); start_button_.set_valign(Gtk::Align::CENTER);
    start_button_.signal_clicked().connect([this] { if (server_.running()) stop_server(); else start_server(); });
    top->append(start_button_); hero->append(*top);
    auto* state = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 10);
    status_badge_.add_css_class("status-badge"); status_badge_.set_halign(Gtk::Align::START); state->append(status_badge_);
    state->append(label("Pinned TLS • Encrypted media • No microphone", "setting-detail")); hero->append(*state);
    live_input_label_.set_xalign(0); live_input_label_.set_hexpand(true); live_input_label_.set_wrap(true);
    live_input_box_.append(live_input_label_); live_input_box_.append(live_input_); hero->append(live_input_box_);
    live_input_.property_active().signal_changed().connect([this] {
        if (live_switch_update_ || !server_.running()) return;
        if (server_.set_input(live_input_.get_active())) {
            record_event(live_input_.get_active() ? "Remote input enable requested" : "Remote input blocked by user");
            live_input_label_.set_text(live_input_.get_active() ? "Enabling remote control…" : "Blocking remote control…");
        }
    });
    root->append(*hero);
    notice_.set_xalign(0); notice_.set_wrap(true); notice_.add_css_class("notice"); notice_.set_visible(false); root->append(notice_);
    switcher_.set_stack(stack_); switcher_.set_halign(Gtk::Align::CENTER); root->append(switcher_);
    stack_.set_vexpand(true); stack_.set_transition_type(Gtk::StackTransitionType::NONE);
    auto* scroll = Gtk::make_managed<Gtk::ScrolledWindow>(); scroll->set_policy(Gtk::PolicyType::NEVER, Gtk::PolicyType::AUTOMATIC);
    settings_box_.set_margin_end(4); scroll->set_child(settings_box_); stack_.add(*scroll, "stream", "Stream");
    auto& capture = section("What to share");
    auto* capture_group = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); capture_group->add_css_class("setting-group");
    source_.set_model(Gtk::StringList::create({"Screen + virtual screens", "One application window", "GNOME virtual desktop", "Test pattern (no capture)"}));
    capture_group->append(row("Capture source", "Your desktop asks what may be shared.", source_));
    spin(monitors_, 1, 3, 1, 2); capture_group->append(row("Screens", "Screen 1 is chosen in the portal; extras are virtual.", monitors_));
    capture.append(*capture_group);
    source_detail_.set_xalign(0); source_detail_.set_wrap(true); source_detail_.add_css_class("setting-detail"); capture.append(source_detail_);
    settings_box_.append(capture);
    auto& permissions = section("Sound & control");
    auto* permissions_group = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); permissions_group->add_css_class("setting-group");
    permissions_group->append(row("Share system sound", "Desktop output only. The microphone is never requested.", audio_));
    permissions_group->append(row("Allow remote control", "Permit headset pointer and keyboard input for this session.", input_));
    permissions.append(*permissions_group);
    permissions.append(label("View-only is the default. You can also disable pointer forwarding in the Quest launcher. Remote input pauses when metuxa loses focus.", "permission-note"));
    settings_box_.append(permissions);
    auto& quality = section("Picture quality");
    auto* quality_group = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); quality_group->add_css_class("setting-group");
    profile_.set_model(Gtk::StringList::create({"Balanced · 72 fps / 8 Mbps", "Sharper · 72 fps / 16 Mbps", "Smoother · 90 fps / 12 Mbps", "Custom"}));
    quality_group->append(row("Quality profile", "Bitrate is a ceiling and adapts to network conditions.", profile_));
    quality.append(*quality_group); settings_box_.append(quality);
    auto* advanced = Gtk::make_managed<Gtk::Expander>("Advanced settings");
    auto* advanced_box = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); advanced_box->add_css_class("setting-group");
    codec_.set_model(Gtk::StringList::create({"HEVC (recommended)", "H.264"})); advanced_box->append(row("Codec preference", "Software fallback may use H.264.", codec_));
    spin(fps_, 24, 120, 1); advanced_box->append(row("Frame rate", "24–120 frames per second", fps_));
    spin(bitrate_, 1000, 80000, 1000, 7); advanced_box->append(row("Maximum bitrate", "1,000–80,000 kbps per screen", bitrate_));
    auto* dimensions = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 6);
    spin(width_, 640, 3840, 2); spin(height_, 480, 2160, 2);
    dimensions->append(width_); dimensions->append(label("×")); dimensions->append(height_);
    advanced_box->append(row("Virtual-screen size", "Physical screens and windows keep their captured size.", *dimensions));
    advanced_box->append(row("Prefer hardware encoding", "Try supported GPU encoders before CPU fallback.", hardware_));
    spin(port_, 1024, 65530, 1); advanced_box->append(row("Base connection port", "Each additional screen uses the next port.", port_));
    advanced_box->append(row("Local discovery", "Advertise metuxa on this network; pairing is still required.", discovery_));
    advanced->set_child(*advanced_box); settings_box_.append(*advanced);
    auto* reset = Gtk::make_managed<Gtk::Button>("Restore safe defaults"); reset->set_halign(Gtk::Align::START); reset->add_css_class("flat");
    reset->signal_clicked().connect([this] { settings_ = {}; load_widgets(); settings_changed(); }); settings_box_.append(*reset);
    // Connection page deliberately separates session credentials from logs.
    auto* pairing_scroll = Gtk::make_managed<Gtk::ScrolledWindow>(); pairing_scroll->set_policy(Gtk::PolicyType::NEVER, Gtk::PolicyType::AUTOMATIC);
    auto* pairing = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 20); pairing->set_margin_end(4);
    pairing->append(label("Pair your headset", "session-title"));
    pairing->append(label("Open the metuxa Quest launcher on the same network. Choose this computer, compare its fingerprint, then enter the pairing code. Only someone with this session’s code can connect."));
    auto& address = section("Computer address"); endpoints_.set_xalign(0); endpoints_.set_wrap(true); endpoints_.set_selectable(true); address.append(endpoints_);
    copy_endpoint_.set_halign(Gtk::Align::START); address.append(copy_endpoint_); pairing->append(address);
    auto& identity = section("TLS fingerprint · SHA-256"); fingerprint_.set_xalign(0); fingerprint_.set_wrap(true); fingerprint_.set_wrap_mode(Pango::WrapMode::CHAR);
    fingerprint_.set_max_width_chars(64); fingerprint_.set_selectable(true); fingerprint_.add_css_class("pair-number"); identity.append(fingerprint_);
    copy_pin_.set_halign(Gtk::Align::START); identity.append(copy_pin_); pairing->append(identity);
    auto& secret = section("Private pairing code");
    auto* secret_line = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 8);
    pairing_code_.set_editable(false); pairing_code_.set_visibility(false); pairing_code_.set_hexpand(true); pairing_code_.add_css_class("pair-number");
    secret_line->append(pairing_code_); secret_line->append(copy_code_); secret.append(*secret_line); secret.append(reveal_code_); pairing->append(secret);
    pairing_hint_.set_xalign(0); pairing_hint_.set_wrap(true); pairing_hint_.add_css_class("permission-note"); pairing->append(pairing_hint_);
    pairing->append(label("A new fingerprint and code are created every time the server starts. Do not publish the code or open these ports to the internet. Discovery is only a convenience, never proof of identity.", "setting-detail"));
    pairing_scroll->set_child(*pairing); stack_.add(*pairing_scroll, "pair", "Pair headset");
    copy_code_.signal_clicked().connect([this] { copy_text(code_, "Pairing code copied. Share it only with your headset."); });
    copy_pin_.signal_clicked().connect([this] { copy_text(pin_, "Fingerprint copied."); });
    copy_endpoint_.signal_clicked().connect([this] { copy_text(primary_endpoint_, "Computer address copied."); });
    reveal_code_.signal_toggled().connect([this] { pairing_code_.set_visibility(reveal_code_.get_active()); });
    auto* activity = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 14);
    activity->append(label("Session activity", "session-title"));
    stream_summary_.set_xalign(0); stream_summary_.set_wrap(true); stream_summary_.set_selectable(true); activity->append(stream_summary_);
    auto* log_actions = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 8);
    log_actions->append(label("Last 300 lines. Sensitive fields are hidden.", "setting-detail"));
    auto* clear = Gtk::make_managed<Gtk::Button>("Clear"); clear->add_css_class("flat");
    clear->signal_clicked().connect([this] { log_lines_.clear(); log_buffer_->set_text(""); }); log_actions->append(*clear); activity->append(*log_actions);
    log_buffer_ = Gtk::TextBuffer::create(); log_view_.set_buffer(log_buffer_); log_view_.set_editable(false); log_view_.set_cursor_visible(false);
    log_view_.set_wrap_mode(Gtk::WrapMode::WORD_CHAR); log_view_.add_css_class("log-view"); log_view_.set_top_margin(12); log_view_.set_bottom_margin(12); log_view_.set_left_margin(12); log_view_.set_right_margin(12);
    auto* log_scroll = Gtk::make_managed<Gtk::ScrolledWindow>(); log_scroll->set_vexpand(true); log_scroll->set_child(log_view_); log_scroll->set_min_content_height(180);
    activity->append(*log_scroll); activity->append(label("Diagnostics include settings and session states only. They exclude raw server output, pairing credentials, network addresses, and captured content.", "setting-detail"));
    stack_.add(*activity, "activity", "Activity"); root->append(stack_); set_child(*root);
    source_.property_selected().signal_changed().connect([this] { if (!loading_) { update_source(); settings_changed(); } });
    profile_.property_selected().signal_changed().connect(sigc::mem_fun(*this, &MainWindow::apply_profile));
    codec_.property_selected().signal_changed().connect([this] { settings_changed(); });
    for (auto* s : {&fps_, &bitrate_, &monitors_, &width_, &height_, &port_}) s->signal_value_changed().connect([this] { settings_changed(); });
    for (auto* s : {&audio_, &input_, &hardware_, &discovery_}) s->property_active().signal_changed().connect([this] { settings_changed(); });
}
void MainWindow::load_widgets() {
    loading_ = true;
    unsigned selected = 0; for (unsigned i = 0; i < 4; ++i) if (settings_.source == sources[i]) selected = i;
    source_.set_selected(selected); codec_.set_selected(settings_.codec == "h264" ? 1 : 0);
    fps_.set_value(settings_.fps); bitrate_.set_value(settings_.bitrate); monitors_.set_value(settings_.monitors);
    width_.set_value(settings_.width); height_.set_value(settings_.height); port_.set_value(settings_.port);
    audio_.set_active(settings_.audio); input_.set_active(settings_.remote_input);
    hardware_.set_active(settings_.hardware); discovery_.set_active(settings_.discovery);
    unsigned profile = settings_.fps == 72 && settings_.bitrate == 8000 ? 0 : settings_.fps == 72 && settings_.bitrate == 16000 ? 1 : settings_.fps == 90 && settings_.bitrate == 12000 ? 2 : 3;
    profile_.set_selected(profile); loading_ = false; update_source(); update_stream_summary();
}
Settings MainWindow::read_widgets() const {
    Settings s;
    s.source = sources[std::min(source_.get_selected(), 3u)]; s.codec = codec_.get_selected() == 1 ? "h264" : "hevc";
    s.fps = fps_.get_value_as_int(); s.bitrate = bitrate_.get_value_as_int(); s.monitors = monitors_.get_value_as_int();
    s.width = width_.get_value_as_int(); s.height = height_.get_value_as_int(); s.port = port_.get_value_as_int();
    s.audio = audio_.get_active(); s.remote_input = input_.get_active(); s.hardware = hardware_.get_active(); s.discovery = discovery_.get_active();
    s.normalize(); return s;
}
void MainWindow::settings_changed() {
    if (loading_ || server_.running()) return;
    settings_ = read_widgets();
    loading_ = true;
    unsigned profile = settings_.fps == 72 && settings_.bitrate == 8000 ? 0 : settings_.fps == 72 && settings_.bitrate == 16000 ? 1 : settings_.fps == 90 && settings_.bitrate == 12000 ? 2 : 3;
    profile_.set_selected(profile);
    width_.set_value(settings_.width); height_.set_value(settings_.height);
    loading_ = false;
    save_timer_.disconnect();
    save_timer_ = Glib::signal_timeout().connect([this] {
        std::string error; if (!save_settings(settings_path(), settings_, error)) { notice_.set_text(error); notice_.set_visible(true); }
        return false;
    }, 500);
    update_stream_summary();
}
void MainWindow::update_source() {
    unsigned i = source_.get_selected();
    static const char* descriptions[] = {
        "Choose a physical display in the system portal. Additional screens require virtual-monitor support from your desktop.",
        "Choose one application window in the system portal. Window sharing is view-only and support depends on your desktop.",
        "GNOME only: choose the first display, then create up to two virtual screens. Your compositor must support the Mutter backend.",
        "Synthetic moving pattern only. No desktop, system sound, microphone, or remote input is used."};
    source_detail_.set_text(descriptions[std::min(i, 3u)]);
    const bool window = i == 1, test = i == 3;
    loading_ = true;
    if (window) monitors_.set_value(1);
    if (window || test) input_.set_active(false);
    if (test) audio_.set_active(false);
    monitors_.set_sensitive(!window); input_.set_sensitive(!window && !test); audio_.set_sensitive(!test);
    width_.set_sensitive(!window); height_.set_sensitive(!window); loading_ = false;
}
void MainWindow::apply_profile() {
    if (loading_ || profile_.get_selected() > 2) return;
    loading_ = true;
    const auto p = profile_.get_selected(); fps_.set_value(p == 2 ? 90 : 72); bitrate_.set_value(p == 0 ? 8000 : p == 1 ? 16000 : 12000);
    loading_ = false; settings_changed();
}
void MainWindow::start_server() {
    if (server_.running()) return;
    settings_ = read_widgets();
    auto executable = resolve_server();
    if (executable.empty()) {
        set_status("Server not found", "Keep metuxa-server beside this app, or add it to your PATH.", "error");
        append_log("metuxa-server was not found. The old MetaShare executable is intentionally not used."); return;
    }
    std::string error;
    if (!save_settings(settings_path(), settings_, error)) { notice_.set_text(error); notice_.set_visible(true); }
    else notice_.set_visible(false);
    save_timer_.disconnect(); startup_timer_.disconnect(); clear_security();
    streams_.clear(); input_available_.clear(); input_active_.clear(); audio_active_.clear(); diagnostic_events_.clear(); had_error_ = false;
    set_status("Starting server…", "Waiting for metuxa to initialize. No connection is claimed yet.", "busy");
    record_event("Starting");
    if (!server_.start(settings_.arguments(executable), error)) {
        set_status("Could not start", "Check Activity for details and verify the server build.", "error"); append_log(error); record_event("Launch failed"); update_actions(); return;
    }
    update_actions(); update_stream_summary();
    startup_timer_ = Glib::signal_timeout().connect([this] {
        if (server_.running() && pin_.empty()) {
            notice_.set_text("The server has not supplied pairing details yet. Check for a system permission dialog or open Activity. You can stop safely at any time."); notice_.set_visible(true);
        }
        return false;
    }, 15000);
}
void MainWindow::stop_server() {
    if (!server_.running() || server_.stopping()) return;
    set_status("Stopping server…", "Closing capture, connections, and remote input.", "busy");
    server_.stop(); record_event("Stop requested"); update_actions();
}
void MainWindow::on_event(const std::string& line) {
    Event event; if (!parse_event(line, event)) { append_log("Ignored an invalid server status message."); return; }
    const auto type = event.get("type"), state = event.get("state");
    if (type == "security") {
        const auto pin = event.get("fingerprint"), code = event.get("pairing_code");
        if (!hex_string(pin, 64) || !hex_string(code, 32)) { append_log("Invalid server pairing information; connection details withheld."); return; }
        pin_ = pin; code_ = code; fingerprint_.set_text(pin_); pairing_code_.set_text(code_);
        auto addresses = local_endpoints(settings_.port);
        primary_endpoint_ = addresses.empty() ? "" : addresses.front();
        std::string text; for (const auto& address : addresses) { if (!text.empty()) text += '\n'; text += address; }
        endpoints_.set_text(text.empty() ? "No active IPv4 network address found. Connect to the same network as your headset." : text);
        pairing_hint_.set_text("Compare the full fingerprint on the headset before pairing. This code authorizes only the current server session.");
        copy_code_.set_sensitive(true); copy_pin_.set_sensitive(true); copy_endpoint_.set_sensitive(!primary_endpoint_.empty()); reveal_code_.set_sensitive(true);
    }
    const int monitor = event.number("monitor", -1);
    if ((type == "stream" || type == "capability") && (monitor < 0 || monitor >= 3)) return;
    if (type == "stream") {
        std::string codec = event.get("codec"); if (codec != "h264" && codec != "hevc") codec = "unknown";
        std::string encoder = event.get("encoder");
        if (encoder.size() > 64 || !std::all_of(encoder.begin(), encoder.end(), [](unsigned char c) { return std::isalnum(c) || c == '_' || c == '-'; })) encoder = "unknown";
        streams_[monitor] = "Screen " + std::to_string(monitor + 1) + ": " + std::to_string(event.number("width")) + " × " + std::to_string(event.number("height")) + " · " + std::to_string(event.number("fps")) + " fps · " + codec + " · " + encoder;
        record_event(streams_[monitor]); update_stream_summary();
    } else if (type == "capability") {
        input_available_[monitor] = event.number("input_available") == 1;
        input_active_[monitor] = event.number("input_active") == 1;
        audio_active_[monitor] = event.number("audio_active") == 1;
        update_actions(); update_stream_summary();
    }
    if (server_.stopping()) return;
    if (state == "starting") set_status("Starting server…", "Initializing the private session.", "busy");
    else if (state == "requesting_access") set_status("Choose what to share", "Approve or cancel the system capture dialog. Nothing is marked connected yet.", "busy");
    else if (state == "listening") {
        set_status("Ready for your headset", "The server is listening. Pair in the Quest launcher to connect.", "ready"); startup_timer_.disconnect(); notice_.set_visible(false);
    } else if (state == "connected") {
        set_status("Headset connected", "An authenticated headset session is active. See Activity for negotiated capture details.", "ready"); startup_timer_.disconnect(); notice_.set_visible(false);
    } else if (state == "error") {
        had_error_ = true; set_status("The server needs attention", "Open Activity for details. Stop the session before changing settings.", "error");
        if (!event.get("message").empty()) append_log(event.get("message"));
        if (!event.get("reason").empty()) append_log(event.get("reason"));
    } else if (state == "stopped") {
        set_status("Finishing shutdown…", "Waiting for the server process to exit.", "busy");
    }
    if (state == "starting" || state == "requesting_access" || state == "listening" || state == "connected" || state == "error" || state == "stopped") record_event(state);
}
void MainWindow::on_exit(int status, bool requested) {
    startup_timer_.disconnect(); clear_security(); update_actions();
    bool failed = !requested && (had_error_ || !WIFEXITED(status) || WEXITSTATUS(status) != 0);
    if (failed) {
        set_status("Server stopped unexpectedly", "Check Activity. Your desktop is no longer being shared; you can change settings and try again.", "error");
        record_event("Unexpected exit: " + std::to_string(WIFEXITED(status) ? WEXITSTATUS(status) : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : status));
    } else {
        set_status("Session stopped", "Capture and remote input are off. Start again to create new pairing details.", "idle"); record_event("Stopped");
    }
    streams_.clear(); input_available_.clear(); input_active_.clear(); audio_active_.clear(); update_stream_summary();
    if (closing_) hide();
}
void MainWindow::update_actions() {
    bool running = server_.running(), stopping = server_.stopping();
    settings_box_.set_sensitive(!running); start_button_.set_sensitive(!stopping);
    start_button_.set_label(stopping ? "Stopping…" : running ? "Stop server" : "Start server");
    start_button_.remove_css_class("suggested-action"); start_button_.remove_css_class("destructive-action");
    start_button_.add_css_class(running ? "destructive-action" : "suggested-action");
    live_input_box_.set_visible(running);
    const bool available = settings_.remote_input && std::any_of(input_available_.begin(), input_available_.end(), [](const auto& item) { return item.second; });
    const bool active = std::any_of(input_active_.begin(), input_active_.end(), [](const auto& item) { return item.second; });
    live_switch_update_ = true; live_input_.set_active(active); live_switch_update_ = false;
    live_input_.set_sensitive(available && !stopping);
    live_input_label_.set_text(!settings_.remote_input ? "View-only session. Remote control is off." : !available ? "Remote control is not available in this session." : active ? "Remote control is allowed. Switch off to block all headset input." : "Remote control is blocked. Viewing continues.");
}
void MainWindow::set_status(const std::string& title, const std::string& detail, const std::string& color) {
    status_title_.set_text(title); status_detail_.set_text(detail);
    for (const auto* cls : {"idle", "busy", "ready", "error"}) status_badge_.remove_css_class(cls);
    status_badge_.add_css_class(color);
    status_badge_.set_text(color == "idle" ? "Stopped" : color == "error" ? "Needs attention" : color == "ready" ? title == "Headset connected" ? "Connected" : "Listening" : "Working");
}
void MainWindow::append_log(const std::string& text) {
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        log_lines_.push_back(timestamp() + "  " + sanitize_log(line));
        while (log_lines_.size() > 300) log_lines_.pop_front();
    }
    // Batch GTK text-buffer work so a chatty child cannot monopolize the UI.
    if (!log_timer_.connected()) log_timer_ = Glib::signal_timeout().connect([this] {
        std::string content;
        for (const auto& entry : log_lines_) content += entry + '\n';
        log_buffer_->set_text(content); auto mark = log_buffer_->get_insert(); log_view_.scroll_to(mark);
        return false;
    }, 100);
}
void MainWindow::record_event(const std::string& text) {
    if (diagnostic_events_.size() >= 200) diagnostic_events_.erase(diagnostic_events_.begin());
    diagnostic_events_.push_back(timestamp() + " " + text); append_log(text);
}
void MainWindow::update_stream_summary() {
    if (!server_.running()) { stream_summary_.set_text("No active session. Capture details appear only after the server reports them."); return; }
    std::string text;
    for (const auto& [monitor, stream] : streams_) {
        text += stream + '\n';
        auto a = audio_active_.find(monitor), i = input_active_.find(monitor);
        if (a != audio_active_.end() || i != input_active_.end()) text += "  System sound: " + std::string(a != audio_active_.end() && a->second ? "active" : "inactive") + " · Remote input: " + std::string(i != input_active_.end() && i->second ? "allowed" : "blocked") + '\n';
    }
    stream_summary_.set_text(text.empty() ? "Waiting for the server to report capture and encoder details…" : text);
}
void MainWindow::clear_security() {
    pin_.clear(); std::fill(code_.begin(), code_.end(), '\0'); code_.clear(); primary_endpoint_.clear();
    pairing_code_.set_text(""); pairing_code_.set_placeholder_text("Available after Start"); fingerprint_.set_text("No active session"); endpoints_.set_text("Start the server to see connection addresses.");
    copy_code_.set_sensitive(false); copy_pin_.set_sensitive(false); copy_endpoint_.set_sensitive(false);
    reveal_code_.set_active(false); reveal_code_.set_sensitive(false);
    pairing_hint_.set_text("Pairing is required. metuxa does not fall back to an unprotected connection.");
}
void MainWindow::copy_text(const std::string& text, const std::string& description) {
    if (text.empty()) return;
    get_clipboard()->set_text(text); notice_.set_text(description); notice_.set_visible(true);
}
void MainWindow::save_diagnostics() {
    diagnostics_button_.set_sensitive(false);
    file_dialog_ = Gtk::FileDialog::create(); file_dialog_->set_title("Save metuxa diagnostics"); file_dialog_->set_initial_name("metuxa-diagnostics.txt");
    file_dialog_->save(*this, sigc::track_obj([this](const Glib::RefPtr<Gio::AsyncResult>& result) {
        diagnostics_button_.set_sensitive(true);
        try {
            auto file = file_dialog_->save_finish(result);
            if (file) {
                auto report = diagnostic_report(settings_, diagnostic_events_);
                // File choice is explicit; no automatic home-directory exports.
                std::string etag; file->replace_contents(report, "", etag, false, Gio::File::CreateFlags::PRIVATE);
                notice_.set_text("Diagnostics saved without credentials, addresses, captured content, or raw logs."); notice_.set_visible(true);
            }
        } catch (const Glib::Error& error) {
            if (!error.matches(GTK_DIALOG_ERROR, GTK_DIALOG_ERROR_DISMISSED) && !error.matches(G_IO_ERROR, G_IO_ERROR_CANCELLED)) {
                notice_.set_text("Could not save diagnostics. Choose a writable local file and try again."); notice_.set_visible(true);
            }
        }
    }, *this), dialogs_cancel_);
}
bool MainWindow::close_requested() {
    if (!server_.running()) {
        save_timer_.disconnect(); std::string error; save_settings(settings_path(), read_widgets(), error); return false;
    }
    if (closing_ || confirming_close_) return true;
    confirming_close_ = true;
    close_dialog_ = Gtk::AlertDialog::create("Stop sharing and close metuxa?");
    close_dialog_->set_detail("This closes the server, disconnects the headset, and stops capture and remote control.");
    close_dialog_->set_buttons({"Keep sharing", "Stop and close"}); close_dialog_->set_cancel_button(0); close_dialog_->set_default_button(0);
    close_dialog_->choose(*this, sigc::track_obj([this](const Glib::RefPtr<Gio::AsyncResult>& result) {
        confirming_close_ = false;
        try {
            if (close_dialog_->choose_finish(result) == 1) {
                closing_ = true;
                if (server_.running()) stop_server(); else hide();
            }
        } catch (const Glib::Error&) {}
    }, *this), dialogs_cancel_);
    return true;
}
