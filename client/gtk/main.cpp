// metuxa: a separately installed control panel, preserving MetaShare identity.
#include "main_window.hpp"

int main(int argc, char* argv[]) {
    auto app = Gtk::Application::create("dev.metuxa.Control", Gio::Application::Flags::DEFAULT_FLAGS);
    return app->make_window_and_run<MainWindow>(argc, argv);
}
