#pragma once
#include <glib.h>
#include <array>
#include <functional>
#include <string>
#include <vector>

namespace metuxa::control {
// Main-loop-owned subprocess. No detached callbacks and no surviving child.
class ServerProcess {
public:
    std::function<void(const std::string&)> on_log;
    std::function<void(const std::string&)> on_event;
    std::function<void(int, bool)> on_exit;
    ServerProcess() = default;
    ~ServerProcess();
    ServerProcess(const ServerProcess&) = delete;
    ServerProcess& operator=(const ServerProcess&) = delete;
    bool start(const std::vector<std::string>& arguments, std::string& error);
    void stop();
    bool set_input(bool enabled);
    bool pairing_command(const std::string& command);
    bool running() const { return pid_ > 0; }
    bool stopping() const { return stopping_; }
private:
    struct Pipe {
        ServerProcess* owner = nullptr;
        int fd = -1;
        guint source = 0;
        bool event = false;
        bool dropping = false;
        std::string pending;
    };
    static gboolean read_ready(gint fd, GIOCondition condition, gpointer data);
    static void child_exited(GPid pid, gint status, gpointer data);
    static gboolean kill_timeout(gpointer data);
    void close_pipes();
    void consume(Pipe& pipe, const char* data, std::size_t length);
    void signal_child(int signal);
    int control_fd_ = -1;
    GPid pid_ = 0;
    guint watch_ = 0;
    guint kill_timer_ = 0;
    bool stopping_ = false;
    std::array<Pipe, 3> pipes_{};
};
} // namespace metuxa::control
