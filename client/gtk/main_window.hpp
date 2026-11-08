#pragma once
#include "control_model.hpp"
#include "app_preferences.hpp"
#include "system_appearance.hpp"
#include "update_manager.hpp"
#include <atomic>
#include <thread>
#include <mutex>
#include <chrono>
#include "server_process.hpp"
#include "gtkmm_compat.hpp"
#include <deque>
#include <map>

class MainWindow : public Gtk::Window {
public:
    MainWindow();
    ~MainWindow() override;
private:
    friend struct ControlUiTest;
    void build_ui();
    void build_preferences();
    void build_about();
    void open_preferences();
    void open_about();
    void apply_preferences();
    void apply_theme();
    void preferences_changed();
    void save_app_preferences();
    void schedule_update_check();
    void begin_update(int action, bool automatic = false);
    void update_controls();
    bool poll_update();
    void reset_pairing_request();
    Gtk::Widget& row(const std::string& title, const std::string& detail, Gtk::Widget& control);
    Gtk::Label& label(const std::string& text, const std::string& style = "");
    Gtk::Box& section(const std::string& title);
    void load_widgets();
    metuxa::control::Settings read_widgets() const;
    void settings_changed();
    void update_source();
    void apply_profile();
    void start_server();
    void stop_server();
    void on_event(const std::string& line);
    void on_exit(int status, bool requested);
    void update_actions();
    void set_status(const std::string& title, const std::string& detail, const std::string& color);
    void append_log(const std::string& text);
    void record_event(const std::string& text);
    void update_stream_summary();
    void save_diagnostics();
    bool close_requested();
    void clear_security();
    void copy_text(const std::string& text, const std::string& description);
    metuxa::control::Settings settings_;
    metuxa::control::AppPreferences preferences_;
    struct UpdateJob {
        std::atomic<bool> cancel{false}, done{false};
        std::atomic<std::uint64_t> downloaded{0}, total{0};
        int action = 0;
        bool automatic = false, success = false;
        metuxa::control::UpdateCheck check = metuxa::control::UpdateCheck::failed;
        metuxa::control::UpdateInfo info;
        std::string error, staging, backup;
    };
    std::shared_ptr<UpdateJob> update_job_;
    std::thread update_thread_;
    metuxa::control::UpdateInfo available_update_;
    std::string staged_update_;
    std::unique_ptr<metuxa::control::SystemAppearance> system_appearance_;
    bool preferences_loading_ = false, update_installed_ = false;
    Glib::RefPtr<Gtk::CssProvider> text_provider_;
    sigc::connection update_timer_, update_poll_, pairing_timer_;
    std::chrono::steady_clock::time_point pairing_deadline_;
    bool pairing_open_ = false;
    std::string pairing_id_;
    Gtk::DropDown theme_, updates_, check_interval_;
    Gtk::SpinButton text_scale_;
    Gtk::Switch animations_, auto_scroll_, remember_window_;
    Gtk::MenuButton menu_button_;
    Gtk::Popover menu_popover_;
    Gtk::Window preferences_window_, about_window_;
    Gtk::Button preferences_button_{"Settings"}, about_button_{"About metuxa"};
    Gtk::Button check_update_{"Check now"}, download_update_{"Download update"}, install_update_{"Install update"}, cancel_update_{"Cancel download"}, skip_update_{"Skip this version"};
    Gtk::Label update_status_, update_detail_;
    Gtk::ProgressBar update_progress_;
    Gtk::Box pair_request_box_{Gtk::Orientation::VERTICAL, 12};
    Gtk::Button pair_open_button_{"Pair a headset"}, pair_accept_{"The codes match"}, pair_reject_{"Reject"};
    Gtk::Label pair_status_, pair_digits_, pair_peer_;
    Gtk::CheckButton pair_compared_{"I compared all 10 digits on my headset"};
    metuxa::control::ServerProcess server_;
    Gtk::HeaderBar header_;
    Gtk::Stack stack_;
    Gtk::StackSwitcher switcher_;
    Gtk::Box settings_box_{Gtk::Orientation::VERTICAL, 18};
    Gtk::Button start_button_{"Start server"};
    Gtk::Button diagnostics_button_{"Save diagnostics…"};
    Gtk::Label status_title_;
    Gtk::Label status_detail_;
    Gtk::Label status_badge_;
    Gtk::Label notice_;
    Gtk::Label stream_summary_;
    Gtk::Label live_input_label_;
    Gtk::Switch live_input_;
    Gtk::Box live_input_box_{Gtk::Orientation::HORIZONTAL, 12};
    Gtk::DropDown source_, profile_, codec_;
    Gtk::SpinButton fps_, bitrate_, monitors_, width_, height_, port_;
    Gtk::Switch audio_, input_, hardware_, discovery_;
    Gtk::Label source_detail_, resolution_detail_;
    Gtk::Label endpoints_, fingerprint_;
    Gtk::Entry pairing_code_;
    Gtk::CheckButton reveal_code_{"Reveal pairing code"};
    Gtk::Button copy_code_{"Copy code"}, copy_pin_{"Copy fingerprint"}, copy_endpoint_{"Copy address"};
    Gtk::Label pairing_hint_;
    Gtk::TextView log_view_;
    Glib::RefPtr<Gtk::TextBuffer> log_buffer_;
    Glib::RefPtr<Gio::Cancellable> dialogs_cancel_;
    Glib::RefPtr<Gtk::AlertDialog> close_dialog_;
    Glib::RefPtr<Gtk::FileDialog> file_dialog_;
    sigc::connection log_timer_;
    sigc::connection save_timer_;
    sigc::connection startup_timer_;
    bool loading_ = false, closing_ = false, confirming_close_ = false, had_error_ = false;
    bool live_switch_update_ = false;
    std::string pin_, code_, primary_endpoint_;
    std::deque<std::string> log_lines_;
    std::vector<std::string> diagnostic_events_;
    std::map<int, std::string> streams_;
    std::map<int, bool> input_available_, input_active_, audio_active_;
};
