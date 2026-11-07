#include "control_model.hpp"
#include "server_process.hpp"
#include <glib.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
using namespace metuxa::control;
namespace {
void check(bool condition, const char* message) {
    if (!condition) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
void pump_until(const std::function<bool()>& done, int timeout_ms = 5000) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    for (;;) {
        if (done()) return;
        if (std::chrono::steady_clock::now() >= deadline) break;
        while (g_main_context_iteration(nullptr, false)) {}
        usleep(1000);
    }
    check(false, "async condition timed out");
}
std::string self_path() {
    std::array<char, 4096> path{};
    auto length = readlink("/proc/self/exe", path.data(), path.size() - 1);
    check(length > 0, "read executable path"); return {path.data(), static_cast<std::size_t>(length)};
}
int child_mode(const std::string& mode) {
    if (mode == "error") { std::cerr << "expected test failure\n"; return 23; }
    if (mode == "stubborn") signal(SIGTERM, SIG_IGN);
    const std::string event = "{\"type\":\"state\",\"state\":\"listening\"}\n";
    write(3, event.data(), 11); usleep(5000); write(3, event.data() + 11, event.size() - 11);
    if (mode == "flood") {
        std::string invalid(100000, 'x'); invalid += '\n';
        write(3, invalid.data(), invalid.size());
        std::string log(4095, 'a'); log += '\n'; write(1, log.data(), log.size());
        write(3, event.data(), event.size());
        return 0;
    }
    std::cout << "test-child=" << getpid() << '\n' << std::flush;
    for (;;) pause();
}
}
int main(int argc, char** argv) {
    if (argc > 2 && std::string(argv[1]) == "--child") return child_mode(argv[2]);
    Settings defaults;
    auto args = defaults.arguments("/fake/metuxa-server");
    check(defaults.fps == 72 && defaults.monitors == 1 && defaults.audio && !defaults.remote_input, "safe defaults");
    check(std::find(args.begin(), args.end(), "--no-input") != args.end(), "view-only explicit argv");
    check(std::find(args.begin(), args.end(), "mic") == args.end(), "microphone never enabled");
    defaults.remote_input = true; args = defaults.arguments("/fake/metuxa-server");
    check(std::find(args.begin(), args.end(), "--input") != args.end(), "remote control explicit argv");
    Settings bad; bad.source = "window"; bad.monitors = 9; bad.remote_input = true; bad.fps = 100000; bad.width = 777; bad.bitrate = -2; bad.port = 99999;
    bad.normalize(); check(bad.monitors == 1 && !bad.remote_input && bad.fps == 120 && bad.width == 776 && bad.bitrate == 1000 && bad.port == 65530, "bounded window settings");
    bad.source = "test"; bad.audio = true; bad.remote_input = true; bad.normalize(); check(!bad.audio && !bad.remote_input, "test source does not capture");
    Event e;
    check(parse_event("{\"state\":\"connected\",\"input_active\":true,\"monitor\":1}", e), "parse JSON status");
    check(e.get("state") == "connected" && e.number("input_active") == 1 && e.number("monitor") == 1, "status fields");
    check(!parse_event("garbage", e) && !parse_event("[]", e) && !parse_event(std::string(9000, 'x'), e), "reject invalid event");
    check(parse_event("{\"type\":\"stream\",\"fps\":\"wrong\",\"monitor\":-2}", e) && e.number("fps", -1) == -1 && e.number("monitor", -1) == -1, "reject bad field types and ranges");
    const std::string secret(32, 'a');
    check(sanitize_log("pairing_code=" + secret).find(secret) == std::string::npos, "redact named secret");
    check(sanitize_log("random " + secret).find(secret) == std::string::npos, "redact unlabelled secret");
    check(sanitize_log("keycode=66").find("66") == std::string::npos, "redact input detail");
    check(g_utf8_validate(sanitize_log(std::string("bad\xff", 4)).c_str(), -1, nullptr), "repair invalid UTF8");
    check(sanitize_log(std::string(9000, 'q')).size() <= 2048, "bound log line");
    char temp[] = "/tmp/metuxa-control-test-XXXXXX"; auto* dir = mkdtemp(temp); check(dir, "make isolated config directory");
    std::string path = std::string(dir) + "/metuxa/control.ini", warning;
    check(load_settings(path, warning) == Settings{}, "missing config defaults");
    Settings saved; saved.fps = 90; saved.bitrate = 12000; saved.remote_input = true;
    check(save_settings(path, saved, warning), "save config atomically");
    auto loaded = load_settings(path, warning); check(loaded.fps == 90 && loaded.bitrate == 12000 && !loaded.remote_input, "persist preferences but not remote-control consent");
    struct stat st{}; check(stat(path.c_str(), &st) == 0 && (st.st_mode & 0777) == 0600, "private settings permissions");
    g_file_set_contents(path.c_str(), "broken [", -1, nullptr); warning.clear();
    check(load_settings(path, warning) == Settings{} && !warning.empty(), "malformed settings fail safe");
    auto report = diagnostic_report(loaded, {"starting", "listening"});
    check(report.find(secret) == std::string::npos && report.find("Source: portal") != std::string::npos, "safe diagnostic fields");
    std::filesystem::remove_all(dir);
    const auto executable = self_path();
    int exited = 0, exit_status = 0, events = 0; bool requested = false; pid_t child_pid = 0;
    ServerProcess server;
    server.on_event = [&](const std::string& line) { Event event; check(parse_event(line, event), "only complete bounded events delivered"); ++events; };
    server.on_exit = [&](int status, bool stop) { ++exited; exit_status = status; requested = stop; };
    server.on_log = [&](const std::string& line) { if (line.rfind("test-child=", 0) == 0) child_pid = std::stoi(line.substr(11)); };
    std::string error;
    check(!server.start({"/does-not-exist/metuxa-server"}, error) && !server.running(), "missing executable cannot become running");
    check(server.start({executable, "--child", "normal"}, error), "start controlled child");
    check(!server.start({executable, "--child", "normal"}, error), "reject double start");
    pump_until([&] { return events == 1 && child_pid > 0; });
    check(server.running(), "real child active");
    server.stop(); server.stop(); check(server.stopping(), "idempotent stop");
    pump_until([&] { return exited == 1; });
    check(requested && !server.running() && kill(child_pid, 0) < 0, "child stopped and reaped");
    check(server.start({executable, "--child", "error"}, error), "restart after exit");
    pump_until([&] { return exited == 2; }); check(WIFEXITED(exit_status) && WEXITSTATUS(exit_status) == 23 && !requested, "preserve failure exit");
    events = 0;
    check(server.start({executable, "--child", "flood"}, error), "start pipe flooding child");
    pump_until([&] { return exited == 3; }); check(events == 2, "drop overlong event and recover framing; exact-buffer log never blocks");
    events = 0; child_pid = 0;
    check(server.start({executable, "--child", "stubborn"}, error), "start unresponsive child");
    pump_until([&] { return child_pid > 0; });
    server.stop(); pump_until([&] { return exited == 4; }, 4500);
    check(WIFSIGNALED(exit_status) && WTERMSIG(exit_status) == SIGKILL && requested, "stop escalates then reaps");
    // A new child must never be hit by a previous session's timer.
    events = 0; child_pid = 0;
    check(server.start({executable, "--child", "normal"}, error), "restart after kill");
    pump_until([&] { return child_pid > 0; });
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(3200);
    while (std::chrono::steady_clock::now() < until) { g_main_context_iteration(nullptr, false); usleep(1000); }
    check(server.running(), "no stale kill timer after restart"); server.stop(); pump_until([&] { return exited == 5; });
    pid_t destroyed_pid = 0;
    {
        ServerProcess temporary;
        temporary.on_log = [&](const std::string& line) { if (line.rfind("test-child=", 0) == 0) destroyed_pid = std::stoi(line.substr(11)); };
        check(temporary.start({executable, "--child", "stubborn"}, error), "start destructor test");
        pump_until([&] { return destroyed_pid > 0; });
    }
    check(kill(destroyed_pid, 0) < 0, "destructor cannot orphan capture process");
    // Simulate an actual controller crash, not just a polite destructor.
    check(prctl(PR_SET_CHILD_SUBREAPER, 1) == 0, "test subreaper setup");
    int crash_pipe[2]; check(pipe(crash_pipe) == 0, "test crash pipe");
    pid_t controller = fork(); check(controller >= 0, "fork test controller");
    if (controller == 0) {
        close(crash_pipe[0]);
        ServerProcess crash_server;
        crash_server.on_log = [&](const std::string& line) {
            if (line.rfind("test-child=", 0) != 0) return;
            pid_t pid = std::stoi(line.substr(11));
            write(crash_pipe[1], &pid, sizeof(pid));
            _exit(0); // intentionally bypass all destructors
        };
        std::string problem;
        if (!crash_server.start({executable, "--child", "stubborn"}, problem)) _exit(2);
        for (;;) g_main_context_iteration(nullptr, true);
    }
    close(crash_pipe[1]); pid_t orphan = 0;
    check(read(crash_pipe[0], &orphan, sizeof(orphan)) == sizeof(orphan) && orphan > 0, "crash child identity");
    close(crash_pipe[0]); int controller_status;
    check(waitpid(controller, &controller_status, 0) == controller && WEXITSTATUS(controller_status) == 0, "test controller exited abruptly");
    int orphan_status = 0; pid_t reaped = 0;
    pump_until([&] { reaped = waitpid(orphan, &orphan_status, WNOHANG); return reaped == orphan; });
    check(WIFSIGNALED(orphan_status) && WTERMSIG(orphan_status) == SIGKILL, "parent-death signal kills orphan even if SIGTERM ignored");
    std::cout << "PASS: settings, privacy, event parsing, pipe framing, failure, repeated start/stop, forced shutdown, stale timers, destructor and crash cleanup\n";
    return 0;
}
