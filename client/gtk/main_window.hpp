#pragma once
#include "control_model.hpp"
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
