#include "main_window.hpp"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <unistd.h>
using namespace metuxa::control;
namespace {
constexpr const char* themes[] = {"system", "dark", "light", "contrast"};
constexpr const char* modes[] = {"off", "notify", "download"};
constexpr int intervals[] = {1, 6, 24, 168};
}
void MainWindow::build_preferences() {
    auto* scroll = Gtk::make_managed<Gtk::ScrolledWindow>();
    scroll->set_policy(Gtk::PolicyType::NEVER, Gtk::PolicyType::AUTOMATIC);
    auto* box = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 18);
    box->set_margin_end(4); scroll->set_child(*box);
    box->append(label("Settings", "session-title"));
    auto& appearance = section("Appearance");
    auto* group = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); group->add_css_class("setting-group");
    theme_.set_model(Gtk::StringList::create({"System", "Dark", "Light", "High contrast"}));
    group->append(row("Theme", "Changes apply now.", theme_));
    text_scale_.set_range(85, 150); text_scale_.set_increments(5, 10); text_scale_.set_numeric(true); text_scale_.set_width_chars(4);
    group->append(row("Text size (%)", "Scale labels and controls in this window.", text_scale_));
    group->append(row("Animations", "Use GTK animations for controls and dialogs.", animations_));
    group->append(row("Remember window size", "Restore the size used last time.", remember_window_));
    group->append(row("Follow new activity", "Scroll to new lines in Activity.", auto_scroll_));
    appearance.append(*group); box->append(appearance);
    auto& updates = section("Updates");
    auto* update_group = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::VERTICAL, 0); update_group->add_css_class("setting-group");
    updates_.set_model(Gtk::StringList::create({"Manual checks only", "Notify me", "Download automatically"}));
    update_group->append(row("New stable releases", "Check GitHub while metuxa is open. Installation always waits for you.", updates_));
    check_interval_.set_model(Gtk::StringList::create({"Every hour", "Every 6 hours", "Daily", "Weekly"}));
    update_group->append(row("Check frequency", "No background service is installed.", check_interval_));
    updates.append(*update_group);
    update_status_.set_xalign(0); update_status_.set_wrap(true); update_status_.set_text(std::string("metuxa v") + METUXA_VERSION); updates.append(update_status_);
    update_detail_.set_xalign(0); update_detail_.set_wrap(true); update_detail_.add_css_class("setting-detail");
    update_detail_.set_text("Updates come from romantrnt/metuxa on GitHub. A pinned signing key verifies the release and its files. The previous AppImage is kept for rollback."); updates.append(update_detail_);
    updates.append(update_progress_); update_progress_.set_visible(false);
    auto* actions = Gtk::make_managed<Gtk::Box>(Gtk::Orientation::HORIZONTAL, 8);
    actions->append(check_update_); actions->append(download_update_); actions->append(install_update_); actions->append(cancel_update_);
    updates.append(*actions); skip_update_.set_halign(Gtk::Align::START); updates.append(skip_update_);
    updates.append(label("The first update from 0.2.0 must be installed manually. Update both the desktop app and Quest when a release requires a newer protocol.", "setting-detail"));
    box->append(updates);
    auto& privacy = section("Privacy and connection");
    privacy.append(label("Sharing starts only when you press Start server. Remote control starts off each time you open metuxa. Pairing approval expires after one minute."));
    privacy.append(label("Stream quality, sound, discovery and network ports are on the Stream tab. Change those settings while the server is stopped. Monitor layout, rotation and panel visibility are in Quest settings.", "setting-detail"));
    box->append(privacy);
    auto* reset = Gtk::make_managed<Gtk::Button>("Reset app preferences"); reset->set_halign(Gtk::Align::START);
    reset->signal_clicked().connect([this] {
        preferences_ = {}; preferences_loading_ = true;
        theme_.set_selected(0); updates_.set_selected(1); check_interval_.set_selected(2); text_scale_.set_value(100);
        animations_.set_active(true); auto_scroll_.set_active(true); remember_window_.set_active(true);
        preferences_loading_ = false; apply_preferences(); save_app_preferences(); schedule_update_check();
    }); box->append(*reset);
    stack_.add(*scroll, "preferences", "Settings");
    preferences_loading_ = true;
    for (unsigned i=0;i<4;++i) if (preferences_.theme == themes[i]) theme_.set_selected(i);
    for (unsigned i=0;i<3;++i) if (preferences_.updates == modes[i]) updates_.set_selected(i);
    for (unsigned i=0;i<4;++i) if (preferences_.check_hours == intervals[i]) check_interval_.set_selected(i);
    text_scale_.set_value(preferences_.text_percent); animations_.set_active(preferences_.animations);
    auto_scroll_.set_active(preferences_.auto_scroll); remember_window_.set_active(preferences_.remember_window);
    preferences_loading_ = false;
    for (auto* dropdown : {&theme_, &updates_, &check_interval_}) dropdown->property_selected().signal_changed().connect([this] { preferences_changed(); });
    for (auto* toggle : {&animations_, &auto_scroll_, &remember_window_}) toggle->property_active().signal_changed().connect([this] { preferences_changed(); });
    text_scale_.signal_value_changed().connect([this] { preferences_changed(); });
    check_update_.signal_clicked().connect([this] { preferences_.skipped_version.clear(); save_app_preferences(); begin_update(1); });
    download_update_.signal_clicked().connect([this] { begin_update(2); });
    install_update_.signal_clicked().connect([this] {
        if (server_.running()) { update_status_.set_text("Stop sharing before installing the update."); return; }
        if (update_installed_) {
            save_app_preferences(); const char* path = g_getenv("APPIMAGE");
            if (path && *path) { char* const args[] = {const_cast<char*>(path), nullptr}; execv(path, args); }
            update_status_.set_text("Close metuxa and reopen its AppImage to use the update."); return;
        }
        begin_update(3);
    });
    cancel_update_.signal_clicked().connect([this] { if (update_job_) { update_job_->cancel = true; cancel_update_.set_sensitive(false); update_status_.set_text("Cancelling…"); } });
    skip_update_.signal_clicked().connect([this] {
        preferences_.skipped_version = available_update_.version; save_app_preferences();
        available_update_ = {}; if (!staged_update_.empty()) { discard_staged_appimage_update(staged_update_); staged_update_.clear(); }
        update_status_.set_text("This version is skipped. Check now to show it again."); update_controls();
    });
    update_controls();
}
void MainWindow::apply_preferences() {
    auto settings = Gtk::Settings::get_default();
    settings->property_gtk_theme_name() = preferences_.theme == "contrast" ? "HighContrast" : original_theme_;
    settings->property_gtk_application_prefer_dark_theme() = preferences_.theme == "dark" || (preferences_.theme == "system" && original_dark_);
    settings->property_gtk_enable_animations() = preferences_.animations;
    if (!text_provider_) { text_provider_ = Gtk::CssProvider::create(); Gtk::StyleProvider::add_provider_for_display(Gdk::Display::get_default(),text_provider_,GTK_STYLE_PROVIDER_PRIORITY_APPLICATION+1); }
    text_provider_->load_from_data("window { font-size: " + std::to_string(14.0 * preferences_.text_percent / 100.0) + "px; }");
    check_interval_.set_sensitive(preferences_.updates != "off");
}
void MainWindow::preferences_changed() {
    if (preferences_loading_) return;
    const auto old_mode = preferences_.updates; const auto old_interval = preferences_.check_hours;
    preferences_.theme = themes[std::min(theme_.get_selected(),3u)]; preferences_.updates = modes[std::min(updates_.get_selected(),2u)];
    preferences_.check_hours = intervals[std::min(check_interval_.get_selected(),3u)]; preferences_.text_percent = text_scale_.get_value_as_int();
    preferences_.animations = animations_.get_active(); preferences_.auto_scroll = auto_scroll_.get_active(); preferences_.remember_window = remember_window_.get_active();
    preferences_.normalize(); apply_preferences(); save_app_preferences();
    if (old_mode != preferences_.updates || old_interval != preferences_.check_hours) {
        if (update_job_ && update_job_->automatic && preferences_.updates == "off") update_job_->cancel = true;
        schedule_update_check();
    }
}
void MainWindow::save_app_preferences() {
    if (preferences_.remember_window && get_width() > 0 && get_height() > 0) { preferences_.window_width = get_width(); preferences_.window_height = get_height(); }
    std::string error; if (!save_preferences(preferences_path(),preferences_,error)) { notice_.set_text(error); notice_.set_visible(true); }
}
void MainWindow::schedule_update_check() {
    update_timer_.disconnect();
    if (preferences_.updates == "off" || g_getenv("METUXA_TEST_ISOLATED") || update_installed_) return;
    update_timer_ = Glib::signal_timeout().connect([this] {
        if (!update_job_) begin_update(1,true);
        update_timer_ = Glib::signal_timeout().connect([this] { if (!update_job_) begin_update(1,true); return true; }, static_cast<unsigned>(preferences_.check_hours * 3600000));
        return false;
    }, 5000);
}
void MainWindow::update_controls() {
    const bool busy = static_cast<bool>(update_job_);
    const bool available = !available_update_.version.empty();
    check_update_.set_sensitive(!busy && !update_installed_);
    download_update_.set_visible(available && staged_update_.empty() && !update_installed_); download_update_.set_sensitive(!busy);
    install_update_.set_visible(!staged_update_.empty() || update_installed_); install_update_.set_sensitive(!busy && !server_.running());
    install_update_.set_label(update_installed_ ? "Restart metuxa" : "Install update");
    cancel_update_.set_visible(busy); cancel_update_.set_sensitive(busy && !update_job_->cancel);
    skip_update_.set_visible(available && !update_installed_); skip_update_.set_sensitive(!busy);
    update_progress_.set_visible(busy);
    start_button_.set_sensitive(!server_.stopping() && !(busy && update_job_->action == 3));
}
void MainWindow::begin_update(int action, bool automatic) {
    if (update_job_ || update_installed_ || (action == 3 && server_.running())) return;
    if (action != 1 && available_update_.version.empty()) return;
    if (action == 3 && staged_update_.empty()) return;
    if (action != 1) { std::string reason; if (!appimage_update_supported(reason)) { update_status_.set_text(reason); return; } }
    if (update_thread_.joinable()) update_thread_.join();
    auto job = std::make_shared<UpdateJob>(); job->action = action; job->automatic = automatic; job->info = available_update_; job->staging = staged_update_;
    update_job_ = job;
    update_status_.set_text(action == 1 ? "Checking for updates…" : action == 2 ? "Downloading update…" : "Verifying and installing…"); update_controls();
    update_thread_ = std::thread([job] {
        auto cancel = [job] { return job->cancel.load(); }; auto progress = [job](std::uint64_t got, std::uint64_t total) { job->downloaded = got; job->total = total; };
        try {
            if (job->action == 1) { job->check = check_for_update(job->info,job->error,cancel); job->success = job->check != UpdateCheck::failed; }
            else if (job->action == 2) { job->staging.clear(); job->success = download_appimage_update(job->info,job->staging,job->error,cancel,progress); }
            else job->success = install_staged_appimage_update(job->info,job->staging,job->backup,job->error,cancel,progress);
        } catch (const std::exception&) { job->error = "The update could not be completed. Try again."; }
        job->done = true;
    });
    update_poll_ = Glib::signal_timeout().connect(sigc::mem_fun(*this,&MainWindow::poll_update),100);
}
bool MainWindow::poll_update() {
    auto job = update_job_; if (!job) return false;
    const auto total = job->total.load();
    if (total) update_progress_.set_fraction(std::min(1.0,static_cast<double>(job->downloaded.load())/total)); else update_progress_.pulse();
    if (!job->done) return true;
    if (update_thread_.joinable()) update_thread_.join();
    update_job_.reset();
    bool auto_download = false;
    if (!job->success) {
        update_status_.set_text(job->cancel ? "Update cancelled." : job->error);
        if (job->action == 2 && !job->staging.empty()) discard_staged_appimage_update(job->staging);
    } else if (job->action == 1) {
        if (job->check == UpdateCheck::available && (!job->automatic || job->info.version != preferences_.skipped_version)) {
            if (available_update_.version != job->info.version && !staged_update_.empty()) { discard_staged_appimage_update(staged_update_); staged_update_.clear(); }
            available_update_ = job->info; update_status_.set_text("metuxa " + job->info.version + " is available.");
            notice_.set_text("metuxa " + job->info.version + " is available. Open Settings to update."); notice_.set_visible(true);
            auto_download = job->automatic && preferences_.updates == "download" && staged_update_.empty();
        } else update_status_.set_text(job->check == UpdateCheck::current ? "metuxa is up to date." : "The latest version is skipped.");
    } else if (job->action == 2) { staged_update_ = job->staging; update_status_.set_text("Update downloaded and verified. Stop sharing, then install it."); }
    else {
        update_installed_ = true; discard_staged_appimage_update(staged_update_); staged_update_.clear(); update_timer_.disconnect();
        update_status_.set_text("Update installed. Restart metuxa when you are ready."); update_detail_.set_text("Previous AppImage saved at: " + job->backup + (job->error.empty() ? "" : "\n" + job->error));
    }
    update_controls();
    if (auto_download) Glib::signal_idle().connect_once(sigc::track_obj([this] { begin_update(2,true); }, *this));
    return false;
}
void MainWindow::reset_pairing_request() {
    pairing_timer_.disconnect(); pairing_id_.clear(); pair_digits_.set_text(""); pair_peer_.set_text(""); pair_compared_.set_active(false);
    pair_accept_.set_sensitive(false); pair_reject_.set_sensitive(true); pair_request_box_.set_visible(false);
}
