// TLS-only, pinned server identity and explicit per-run pairing authentication.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <netinet/in.h>

#include "signaling.hpp"
#include "secure_transport.hpp"

namespace metashare::signal {

class Server {
  public:
    using OnMessage = std::function<void(const Message&)>;
    using OnDisconnect = std::function<void()>;
    // Fired only after TLS and pairing authentication, before application reads.
    // peer holds the client's TCP source address (so the owner can target the
    // same host for UDP media).
    using OnConnect = std::function<void(const sockaddr_in& peer)>;

    Server() = default;
    ~Server();

    Server(const Server&) = delete;
    Server& operator=(const Server&) = delete;

    // Start listening on the given port. on_connect is invoked once per
    // accepted client (server thread); on_message is invoked for each received
    // message. At most one client is supported at a time; a second connect
    // blocks until the first disconnects.
    bool start(std::uint16_t port, OnConnect on_connect, OnMessage on_message,
               std::string& err);
    void stop();
    void set_security(std::shared_ptr<security::ServerCredentials> credentials) { credentials_ = std::move(credentials); }
    std::uint16_t bound_port() const { return bound_port_; }

    // True only after TLS and pairing authentication.
    bool has_client() const;

    // Called once after each authenticated session, including EOF and timeout.
    OnDisconnect on_disconnect;

    // Send a message to the connected client. Returns false if no client
    // is connected or the write fails.
    bool send(const Message& m);

  private:
    void client_loop(int fd, const sockaddr_in& peer,
                     const OnConnect& on_connect, const OnMessage& on_message);

    void pairing_loop(int fd, const sockaddr_in& peer, std::string_view commitment, std::string& pending);
    bool pairing_peer_alive(int fd);
    bool read_line(int fd, std::string& line, std::string& pending, int timeout_ms);
    bool write_line_locked(const std::string& line);
    std::shared_ptr<security::ServerCredentials> credentials_;
    SSL* tls_ = nullptr; // protected by send_mu_
    std::atomic<bool> authenticated_{false};
    std::uint16_t bound_port_ = 0;
    int listen_fd_ = -1;
    std::atomic<bool> running_{false};
    std::thread accept_thread_;

    std::atomic<int> client_fd_{-1};
    mutable std::mutex send_mu_;
};

}  // namespace metashare::signal
