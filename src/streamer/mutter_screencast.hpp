// Mutter ScreenCast session hosting one or more *virtual* monitors.
//
// Talks directly to `org.gnome.Mutter.ScreenCast` over the session D-Bus,
// bypassing xdg-desktop-portal entirely. This is what gnome-remote-desktop uses
// under the hood. The direct API is experimental and depends on compositor
// policy. Normal user sessions use the portal VIRTUAL source, which also
// creates session-owned virtual monitor space with explicit user consent.
//
// GNOME 44+ flow: Mutter removed `OpenPipeWireRemote` in 44, so after
// `session.Start()` each stream emits `PipeWireStreamAdded(node_id)` and we
// connect to the default PipeWire socket ourselves (`pw_context_connect`, no
// fd). Mutter API v3 (GNOME 41+) added `RecordVirtual`; we require API v4
// (GNOME 43+) as a proxy for "the new PipeWire flow", which in practice means
// GNOME 44+.
//
// All virtual monitors must be added before start(). After start(), each
// monitor has a PipeWire node_id which the caller binds to a pw_stream.

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace sdbus {
class IConnection;
class IProxy;
}

namespace metashare {

class MutterScreenCastSession {
  public:
    MutterScreenCastSession();
    ~MutterScreenCastSession();

    MutterScreenCastSession(const MutterScreenCastSession&) = delete;
    MutterScreenCastSession& operator=(const MutterScreenCastSession&) = delete;

    // Connect to the session D-Bus and call CreateSession. Returns false
    // and sets err when the compositor or its policy rejects the call.
    bool open(std::string& err);

    // Add a virtual monitor of the given size. Must be called between open()
    // and start(). Returns the 0-based monitor index, or -1 on error.
    int add_virtual_monitor(int width, int height, std::string& err);

    // Call session.Start() and block until PipeWireStreamAdded has fired for
    // every virtual monitor. On success, MonitorInfo::node_id is populated
    // for each monitor and the caller can attach PipeWire streams.
    bool start(std::string& err, int timeout_s = 10);

    // Stop the session and release D-Bus resources. Idempotent.
    void close();

    struct MonitorInfo {
        int width = 0;
        int height = 0;
        std::string stream_path;    // D-Bus object path of the Mutter stream
        std::uint32_t node_id = 0;  // PipeWire node id, valid after start()
    };

    const std::vector<MonitorInfo>& monitors() const { return monitors_; }

  private:
    bool check_version(std::string& err);

    std::unique_ptr<sdbus::IConnection> conn_;
    std::string session_path_;
    std::vector<MonitorInfo> monitors_;
    // Signal subscriptions must survive until the connection event loop stops.
    std::vector<std::unique_ptr<sdbus::IProxy>> stream_proxies_;
    std::mutex mu_;
    std::atomic<int> pending_count_{0};
    bool open_ = false;
};

}  // namespace metashare
