#include "server_process.hpp"
#include <glib-unix.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>

namespace metuxa::control {
namespace {
void prepare_child(gpointer data) {
    const auto parent = *static_cast<pid_t*>(data);
    // Only async-signal-safe syscalls between fork and exec. No shell, no secrets
    // in argv. A hard parent crash must not leave capture/input running.
    if (setpgid(0, 0) < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent) _exit(126);
}
}
ServerProcess::~ServerProcess() {
    if (kill_timer_) g_source_remove(kill_timer_);
    if (watch_) g_source_remove(watch_);
    close_pipes();
    if (pid_ > 0) {
        signal_child(SIGTERM);
        int status = 0;
        for (int i = 0; i < 20; ++i) {
            pid_t result = waitpid(pid_, &status, WNOHANG);
            if (result == pid_ || (result < 0 && errno == ECHILD)) { pid_ = 0; break; }
            usleep(10000);
        }
        if (pid_ > 0) {
            signal_child(SIGKILL);
            while (waitpid(pid_, &status, 0) < 0 && errno == EINTR) {}
            g_spawn_close_pid(pid_);
        }
    }
}
bool ServerProcess::start(const std::vector<std::string>& arguments, std::string& error) {
    if (running() || arguments.empty()) { error = "A server is already active, or its command is empty."; return false; }
    int status_pipe[2];
    if (pipe2(status_pipe, O_CLOEXEC) != 0) { error = "Cannot create private status pipe."; return false; }
    std::vector<const gchar*> argv;
    for (const auto& a : arguments) argv.push_back(a.c_str());
    argv.push_back(nullptr);
    int target = 3, output = -1, errors = -1;
    auto parent = getpid();
    GError* e = nullptr;
    bool ok = g_spawn_async_with_pipes_and_fds(nullptr, argv.data(), nullptr,
        static_cast<GSpawnFlags>(G_SPAWN_DO_NOT_REAP_CHILD), prepare_child, &parent,
        -1, -1, -1, &status_pipe[1], &target, 1, &pid_, nullptr, &output, &errors, &e);
    close(status_pipe[1]);
    if (!ok) {
        close(status_pipe[0]); pid_ = 0;
        error = e ? e->message : "Server could not be launched."; g_clear_error(&e); return false;
    }
    stopping_ = false;
    std::array<int, 3> descriptors{output, errors, status_pipe[0]};
    for (std::size_t i = 0; i < pipes_.size(); ++i) {
        auto& p = pipes_[i]; p = {}; p.owner = this; p.fd = descriptors[i]; p.event = i == 2;
        auto flags = fcntl(p.fd, F_GETFL);
        if (flags < 0 || fcntl(p.fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            error = "Cannot make server output non-blocking.";
            signal_child(SIGKILL);
            int result; while (waitpid(pid_, &result, 0) < 0 && errno == EINTR) {}
            g_spawn_close_pid(pid_); pid_ = 0;
            for (int fd : descriptors) if (fd >= 0) close(fd);
            for (auto& pipe : pipes_) { if (pipe.source) g_source_remove(pipe.source); pipe = {}; }
            return false;
        }
        p.source = g_unix_fd_add_full(G_PRIORITY_DEFAULT, p.fd,
            static_cast<GIOCondition>(G_IO_IN | G_IO_HUP | G_IO_ERR), read_ready, &p, nullptr);
    }
    watch_ = g_child_watch_add(pid_, child_exited, this);
    return true;
}
void ServerProcess::signal_child(int signal) {
    if (pid_ <= 0) return;
    // The process group was established before exec. PID is held until reaped.
    if (kill(-pid_, signal) < 0 && errno == ESRCH) kill(pid_, signal);
}
void ServerProcess::stop() {
    if (!running() || stopping_) return;
    stopping_ = true;
    signal_child(SIGTERM);
    kill_timer_ = g_timeout_add(3000, kill_timeout, this);
}
bool ServerProcess::set_input(bool enabled) {
    if (!running() || stopping_) return false;
    return kill(pid_, enabled ? SIGUSR2 : SIGUSR1) == 0;
}
gboolean ServerProcess::kill_timeout(gpointer data) {
    auto* self = static_cast<ServerProcess*>(data);
    self->kill_timer_ = 0;
    if (self->running() && self->stopping_) self->signal_child(SIGKILL);
    return G_SOURCE_REMOVE;
}
void ServerProcess::consume(Pipe& p, const char* data, std::size_t length) {
    for (std::size_t i = 0; i < length; ++i) {
        char c = data[i];
        if (c == '\n') {
            if (!p.dropping && !p.pending.empty()) {
                if (p.event) { if (on_event) on_event(p.pending); }
                else if (on_log) on_log(p.pending);
            }
            p.pending.clear(); p.dropping = false;
        } else if (!p.dropping) {
            if (p.pending.size() >= 8192) { p.pending.clear(); p.dropping = true; }
            else p.pending.push_back(c);
        }
    }
}
gboolean ServerProcess::read_ready(gint fd, GIOCondition condition, gpointer data) {
    auto* p = static_cast<Pipe*>(data);
    char buffer[4096];
    // Bound work per tick so a noisy server cannot freeze Start/Stop or close.
    std::size_t read_total = 0;
    while (read_total < 65536) {
        auto count = read(fd, buffer, sizeof(buffer));
        if (count > 0) { read_total += count; p->owner->consume(*p, buffer, count); continue; }
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK) && !(condition & (G_IO_HUP | G_IO_ERR))) return G_SOURCE_CONTINUE;
        if (!p->pending.empty() && !p->event && !p->dropping && p->owner->on_log) p->owner->on_log(p->pending);
        p->pending.clear(); p->source = 0; close(p->fd); p->fd = -1;
        return G_SOURCE_REMOVE;
    }
    return G_SOURCE_CONTINUE;
}
void ServerProcess::close_pipes() {
    for (auto& p : pipes_) {
        if (p.source) g_source_remove(p.source);
        if (p.fd >= 0) close(p.fd);
        p = {};
    }
}
void ServerProcess::child_exited(GPid pid, gint status, gpointer data) {
    auto* self = static_cast<ServerProcess*>(data);
    if (pid != self->pid_) return;
    // Drain final errors before closing pipes, without blocking.
    for (auto& p : self->pipes_) {
        if (p.fd < 0) continue;
        char buffer[4096];
        for (int i = 0; i < 16; ++i) {
            auto n = read(p.fd, buffer, sizeof(buffer));
            if (n > 0) self->consume(p, buffer, n); else break;
        }
    }
    self->watch_ = 0;
    if (self->kill_timer_) { g_source_remove(self->kill_timer_); self->kill_timer_ = 0; }
    self->close_pipes();
    const bool requested = self->stopping_;
    self->pid_ = 0; self->stopping_ = false;
    g_spawn_close_pid(pid);
    if (self->on_exit) self->on_exit(status, requested);
}
} // namespace metuxa::control
