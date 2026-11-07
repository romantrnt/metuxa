#include "discovery.hpp"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>

#include "protocol.hpp"

namespace metashare {

DiscoveryResponder::~DiscoveryResponder() { stop(); }

bool DiscoveryResponder::start(std::uint16_t signaling_port,
                               std::uint16_t monitor_count, std::string& err) {
    if (running_ || thread_.joinable()) { err="Discovery already started"; return false; }
    signaling_port_ = signaling_port;
    monitor_count_ = monitor_count;
    fd_ = ::socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd_ < 0) {
        err = std::string("discovery socket: ") + std::strerror(errno);
        return false;
    }
    int yes = 1;
    ::setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(proto::kDiscoveryPort);
    if (::bind(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        err = std::string("discovery bind: ") + std::strerror(errno);
        ::close(fd_);
        fd_ = -1;
        return false;
    }

    timeval timeout{0, 200000};
    ::setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    running_ = true;
    thread_ = std::thread([this] { serve_loop(); });
    return true;
}

void DiscoveryResponder::stop() {
    running_ = false;
    if (fd_ >= 0) ::shutdown(fd_, SHUT_RDWR);
    // Do not close/reuse or modify the descriptor while serve_loop accesses it.
    if (thread_.joinable()) thread_.join();
    if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
}

void DiscoveryResponder::serve_loop() {
    auto window = std::chrono::steady_clock::now();
    unsigned replies = 0;
    proto::DiscoveryOffer offer{};
    std::memcpy(offer.magic, proto::kDiscoveryMagic, sizeof(offer.magic));
    offer.version = proto::kProtocolVersion;
    offer.signaling_port = signaling_port_;
    offer.monitor_count = monitor_count_;
    if (::gethostname(offer.host_name, sizeof(offer.host_name)) != 0)
        std::snprintf(offer.host_name, sizeof(offer.host_name), "metuxa");
    offer.host_name[sizeof(offer.host_name) - 1] = '\0';
    while (running_) {
        proto::DiscoveryProbe probe{};
        sockaddr_in from{};
        socklen_t flen = sizeof(from);
        ssize_t n = ::recvfrom(fd_, &probe, sizeof(probe), MSG_TRUNC,
                               reinterpret_cast<sockaddr*>(&from), &flen);
        if (n < 0) {
            if (!running_) break;
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            break;
        }
        if (!running_) break;
        // Linux MSG_TRUNC returns the original datagram length, so oversized
        // packets cannot masquerade as a valid truncated probe.
        if (static_cast<std::size_t>(n) != sizeof(probe) || from.sin_family != AF_INET || !from.sin_port) continue;
        if (std::memcmp(probe.magic, proto::kDiscoveryMagic,
                        sizeof(probe.magic)) != 0)
            continue;
        if (probe.version != proto::kProtocolVersion) continue;

        const auto now = std::chrono::steady_clock::now();
        if (now - window >= std::chrono::seconds(1)) { window = now; replies = 0; }
        // Discovery is unauthenticated. Bound replies globally and never log
        // each probe, which would let LAN traffic block shutdown on stderr.
        if (++replies > 20) continue;

        ::sendto(fd_, &offer, sizeof(offer), MSG_DONTWAIT,
                 reinterpret_cast<sockaddr*>(&from), flen);
    }
}

}  // namespace metashare
