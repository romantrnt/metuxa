#include "rtp_server.hpp"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <string>
#include <sstream>
#include <stdexcept>
#include <vector>

namespace metashare {

namespace {

// Monotonic source of globally-unique SSRCs across all RtpServer instances.
std::uint32_t next_ssrc() {
    static std::atomic<std::uint32_t> counter{0x12340000};
    return counter.fetch_add(0x100, std::memory_order_relaxed);
}

// READY is intentionally one small, strict JSON object, not a general parser.
bool parse_ready_port(const std::string& body, std::uint16_t& port_out) {
    if (body.size() < 10 || body.size() > 24 || body.compare(0,8,"{\"port\":") != 0 || body.back() != '}') return false;
    std::size_t i=8; unsigned value=0;
    if (body[i]<'1' || body[i]>'9') return false;
    for (;i+1<body.size();++i) {
        if (body[i]<'0' || body[i]>'9') return false;
        value=value*10+static_cast<unsigned>(body[i]-'0');
        if(value>65535) return false;
    }
    if (!value) return false;
    port_out=static_cast<std::uint16_t>(value);
    return true;
}

}  // namespace

RtpServer::RtpServer() : video_ssrc_(next_ssrc()), audio_ssrc_(next_ssrc()) {}

RtpServer::~RtpServer() { stop(); }

bool RtpServer::start(std::uint16_t signaling_port, std::string& err) {
    // Build the packetizers up front so the HELLO and the wire agree on
    // SSRC/PT.
    if (video_.codec == proto::Codec::kH265) {
        video_packer_ = std::make_unique<rtp::H265RtpPacketizer>(video_ssrc_);
    } else {
        video_packer_ = std::make_unique<rtp::H264RtpPacketizer>(video_ssrc_);
    }
    audio_packer_ = std::make_unique<rtp::OpusRtpPacketizer>(audio_ssrc_);

    if (!open_udp(err)) return false;

    running_ = true;
    nack_thread_ = std::thread([this] { nack_loop(); });
    sender_thread_ = std::thread([this] { sender_loop(); });

    signaling_.on_disconnect = [this] { on_disconnect(); };
    if (!signaling_.start(
            signaling_port,
            [this](const sockaddr_in& peer) { on_connect(peer); },
            [this](const signal::Message& m) { on_message(m); }, err)) {
        stop();
        return false;
    }
    std::fprintf(stderr, "[rtp] signaling on tcp/%u, video ssrc=%u\n",
                 signaling_port, video_ssrc_);
    return true;
}

bool RtpServer::open_udp(std::string& err) {
    udp_fd_ = ::socket(AF_INET, SOCK_DGRAM, 0);
    if (udp_fd_ < 0) {
        err = std::string("udp socket: ") + std::strerror(errno);
        return false;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(0);  // ephemeral source port
    if (::bind(udp_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        err = std::string("udp bind: ") + std::strerror(errno);
        ::close(udp_fd_);
        udp_fd_ = -1;
        return false;
    }
    // Bump socket buffers: large frames (4K) produce 100+ RTP packets per
    // access unit; the default ~208 KB kernel buffers overflow during bursts.
    int rcvbuf = 1 << 20;  // 1 MB for incoming RTCP
    ::setsockopt(udp_fd_, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    int sndbuf = 4 << 20;  // 4 MB for outgoing media bursts
    ::setsockopt(udp_fd_, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));
    return true;
}

void RtpServer::stop() {
    signaling_.stop();
    running_ = false;
    send_cv_.notify_all();  // wake the sender thread out of its wait
    if (udp_fd_ >= 0) {
        // Wake the NACK recvfrom.
        ::shutdown(udp_fd_, SHUT_RDWR);
    }
    if (nack_thread_.joinable()) nack_thread_.join();
    // Join the sender before closing the socket it writes to.
    if (sender_thread_.joinable()) sender_thread_.join();
    if (udp_fd_ >= 0) {
        ::close(udp_fd_);
        udp_fd_ = -1;
    }
    on_disconnect();
}

void RtpServer::set_pacing_bitrate(int kbps) {
    std::lock_guard<std::mutex> lk(peer_mu_);
    if (pacing_kbps_ == kbps) return;
    pacing_kbps_ = kbps;
    if (pacer_) pacer_->set_stream_rate(id_, peer_streaming_ ? kbps : 0);
}

void RtpServer::on_disconnect() {
    bool was_connected;
    {
        std::lock_guard<std::mutex> lk(peer_mu_);
        was_connected = session_connected_;
        session_connected_ = false;
        peer_streaming_ = false;
        peer_udp_ = {};
        media_send_.clear(); media_receive_.clear();
        if (pacer_) pacer_->set_stream_rate(id_, 0);
    }
    reset_video_queue();
    if (was_connected && on_input_release) on_input_release();
    if (was_connected && on_client_state) on_client_state(false);
}

int RtpServer::peer_count() const {
    std::lock_guard<std::mutex> lk(peer_mu_);
    return peer_streaming_ && !display_reconnect_ && !display_failed_ ? 1 : 0;
}

void RtpServer::update_display_format(const VideoFormat& v) {
    std::scoped_lock lock(peer_mu_, send_mu_);
    video_ = v;
    if (v.codec == proto::Codec::kH265)
        video_packer_ = std::make_unique<rtp::H265RtpPacketizer>(video_ssrc_);
    else
        video_packer_ = std::make_unique<rtp::H264RtpPacketizer>(video_ssrc_);
}

void RtpServer::on_connect(const sockaddr_in& peer) {
    // The signaling thread serializes reconnect behind any in-flight resize.
    if (display_failed_) throw std::runtime_error("Virtual monitor needs sharing to be restarted");
    {
        std::lock_guard<std::mutex> lk(peer_mu_);
        peer_udp_ = peer;
        peer_udp_.sin_port = 0;
        peer_streaming_ = false;
        session_connected_ = true;
        display_reconnect_ = false;
        if (!media_send_.generate(security::MediaCipher::kServer) ||
            !media_receive_.generate(security::MediaCipher::kClient))
            throw std::runtime_error("Unable to create session keys");
    }
    reset_video_queue();
    send_hello();
}

void RtpServer::send_hello() {
    sockaddr_in local{}; socklen_t size=sizeof(local);
    if (::getsockname(udp_fd_,reinterpret_cast<sockaddr*>(&local),&size)!=0)
        throw std::runtime_error("Unable to describe media socket");
    std::ostringstream body;
    {
        std::lock_guard<std::mutex> lk(peer_mu_);
        const char* codec = (video_.codec == proto::Codec::kH265) ? "h265" : "h264";
        body << "{\"version\":5,\"monitor_count\":" << monitor_count_ << ",\"input\":" << (input_enabled_ && on_input ? "true":"false")
             << ",\"display\":{\"source\":\"" << display::name(display_source_)
             << "\",\"orientation\":" << (orientation_enabled_ && on_display_orientation ? "true" : "false") << "}"
             << ",\"udp_port\":" << ntohs(local.sin_port)
             << ",\"security\":{\"version\":1,\"cipher\":\"AES-256-GCM\",\"s2c_key\":\"" << media_send_.key_hex()
             << "\",\"s2c_prefix\":\"" << media_send_.prefix_hex()
             << "\",\"c2s_key\":\"" << media_receive_.key_hex()
             << "\",\"c2s_prefix\":\"" << media_receive_.prefix_hex() << "\"},"
             << "\"video\":{\"codec\":\"" << codec << "\",\"width\":" << video_.width
             << ",\"height\":" << video_.height << ",\"fps\":" << video_.fps_num
             << ",\"pt\":" << unsigned(rtp::kVideoPayloadType) << ",\"ssrc\":" << video_ssrc_
             << ",\"clock\":" << rtp::kVideoClockRate << "}";
        if (audio_enabled_ && id_ == 0)
            body << ",\"audio\":{\"codec\":\"opus\",\"rate\":" << audio_rate_
                 << ",\"channels\":" << audio_channels_ << ",\"pt\":" << unsigned(rtp::kOpusPayloadType)
                 << ",\"ssrc\":" << audio_ssrc_ << ",\"clock\":" << rtp::kOpusClockRate << "}";
        body << "}";
    }
    if(!signaling_.send({signal::Type::kHello,body.str()})) throw std::runtime_error("HELLO failed");
}

bool RtpServer::send_secure(const std::uint8_t* data, std::size_t size) {
    if (!peer_streaming_ || display_reconnect_ || display_failed_) return false;
    std::vector<std::uint8_t> sealed;
    if (!media_send_.seal(data,size,sealed)) return false;
    return ::sendto(udp_fd_,sealed.data(),sealed.size(),MSG_DONTWAIT,
                    reinterpret_cast<sockaddr*>(&peer_udp_),sizeof(peer_udp_)) == static_cast<ssize_t>(sealed.size());
}

void RtpServer::on_message(const signal::Message& m) {
    if (m.type == signal::Type::kReady) {
        std::uint16_t port = 0;
        if (!parse_ready_port(m.body, port)) {
            std::fprintf(stderr, "[rtp] malformed READY\n");
            throw std::runtime_error("Invalid READY");
        }
        {
            std::lock_guard<std::mutex> lk(peer_mu_);
            if (peer_streaming_) throw std::runtime_error("Duplicate READY");
            if (peer_udp_.sin_family != AF_INET ||
                peer_udp_.sin_addr.s_addr == 0) {
                std::fprintf(stderr, "[rtp] READY before connect\n");
                return;
            }
            peer_udp_.sin_port = htons(port);
            peer_streaming_ = true;
            if (pacer_) pacer_->set_stream_rate(id_, pacing_kbps_);
        }
        char ip[INET_ADDRSTRLEN] = {0};
        {
            std::lock_guard<std::mutex> lk(peer_mu_);
            ::inet_ntop(AF_INET, &peer_udp_.sin_addr, ip, sizeof(ip));
        }
        std::fprintf(stderr, "[rtp] client ready -> udp %s:%u\n", ip, port);
        if (!signaling_.send({signal::Type::kStart, ""}))
            throw std::runtime_error("START failed");
        if (!signaling_.send({signal::Type::kInputState, input_enabled_ && on_input ? "true" : "false"}))
            throw std::runtime_error("Input capability update failed");
        if (on_client_state) on_client_state(true);
        if (on_keyframe_request) on_keyframe_request();
    } else if (m.type == signal::Type::kDisplayOrientation) {
        bool supported, busy;
        {
            std::lock_guard lock(peer_mu_);
            if (!peer_streaming_) throw std::runtime_error("DISPLAY_ORIENTATION before READY");
            supported = display_source_ == display::Source::Virtual && orientation_enabled_ && on_display_orientation;
            const auto now = std::chrono::steady_clock::now();
            busy = display_reconnect_ || now - last_orientation_ < std::chrono::seconds(2);
            if (supported && !busy) {
                display_reconnect_ = true;
                last_orientation_ = now;
            }
        }
        std::string result;
        if (!supported) result = "unsupported";
        else if (busy) result = "busy";
        else {
            reset_video_queue();
            if (on_input_release) on_input_release();
            const bool changed = on_display_orientation(m.body == "portrait");
            std::lock_guard lock(peer_mu_);
            result = display_failed_ ? "restart_required" :
                changed ? "ok " + std::to_string(video_.width) + " " + std::to_string(video_.height) : "failed";
        }
        if (!signaling_.send({signal::Type::kDisplayResult, result}))
            throw std::runtime_error("Display orientation result failed");
        if (result == "restart_required")
            throw std::runtime_error("Virtual monitor restore failed; restart sharing");
    } else if (m.type == signal::Type::kInput) {
        bool ready;
        { std::lock_guard lk(peer_mu_); ready=peer_streaming_; if (display_reconnect_) return; }
        if (!ready) throw std::runtime_error("INPUT before READY");
        if (m.body == "r") { if (on_input_release) on_input_release(); return; }
        if (input_enabled_ && on_input) {
            input::Event e;
            if (input::parse(m.body, e)) on_input(e);
        }
    } else if (m.type == signal::Type::kPing) {
        if (!signaling_.send({signal::Type::kPing, ""}) ||
            !signaling_.send({signal::Type::kInputState, input_enabled_ && on_input ? "true" : "false"}))
            throw std::runtime_error("Heartbeat failed");
    } else if (m.type == signal::Type::kBye) {
        on_disconnect();
        std::fprintf(stderr, "[rtp] client bye\n");
    }
}

void RtpServer::reset_video_queue() {
    std::lock_guard<std::mutex> lk(send_mu_);
    send_q_.clear();
    repair_q_.clear();
    video_retx_.clear();
    audio_retx_.clear();
    queued_packets_ = 0;
    ++send_generation_;
    awaiting_keyframe_ = true;
}

void RtpServer::broadcast_video(const std::uint8_t* data, std::size_t size,
                                std::int64_t pts_usec, bool keyframe) {
    const auto deadline = std::chrono::steady_clock::now() + kMaxSendAge;
    bool request_keyframe = false;
    {
        // Keep the connection and queue generation stable while packetizing.
        std::scoped_lock lk(peer_mu_, send_mu_);
        if (!peer_streaming_ || display_reconnect_ || display_failed_ || (awaiting_keyframe_ && !keyframe)) return;
        std::vector<rtp::Packet> packets;
        video_packer_->packetize(data, size, pts_usec, packets);
        if (packets.empty()) return;

        constexpr std::size_t kMaxQueuedPackets = 4096;  // memory bound only
        if ((!send_q_.empty() &&
             std::chrono::steady_clock::now() >= send_q_.front().deadline) ||
            queued_packets_ + packets.size() > kMaxQueuedPackets) {
            send_q_.clear();
            repair_q_.clear();
            video_retx_.clear();
            queued_packets_ = 0;
            ++send_generation_;  // cancels the frame already being paced
            awaiting_keyframe_ = true;
            send_drops_.fetch_add(1, std::memory_order_relaxed);
        }
        if ((awaiting_keyframe_ && !keyframe) ||
            packets.size() > kMaxQueuedPackets) {
            awaiting_keyframe_ = true;
            request_keyframe = true;
        } else {
            awaiting_keyframe_ = false;
            queued_packets_ += packets.size();
            send_q_.push_back({std::move(packets), deadline, send_generation_});
        }
    }
    if (request_keyframe && on_keyframe_request) on_keyframe_request();
    send_cv_.notify_one();
}

void RtpServer::sender_loop() {
    while (running_) {
        VideoFrame frame;
        {
            std::unique_lock<std::mutex> lk(send_mu_);
            send_cv_.wait(lk, [this] {
                return !running_ || !send_q_.empty() || !repair_q_.empty();
            });
            if (!running_) break;
            lk.unlock();
            send_repairs();
            lk.lock();
            if (send_q_.empty()) continue;
            frame = std::move(send_q_.front());
            send_q_.pop_front();
            queued_packets_ -= frame.packets.size();
        }
        for (const auto& pkt : frame.packets) {
            if (!running_) break;
            send_repairs();
            const bool paced =
                !pacer_ || pacer_->consume(pkt.size(), frame.deadline);
            bool request_keyframe = false;
            bool sent = false;
            {
                std::scoped_lock lk(peer_mu_, send_mu_);
                if (!peer_streaming_ || frame.generation != send_generation_)
                    break;
                if (paced &&
                    std::chrono::steady_clock::now() < frame.deadline) {
                    sent = send_secure(pkt.data(), pkt.size());
                }
                if (!sent) {
                    // A partial access unit invalidates every dependent frame.
                    send_q_.clear();
                    repair_q_.clear();
                    video_retx_.clear();
                    queued_packets_ = 0;
                    ++send_generation_;
                    awaiting_keyframe_ = true;
                    send_drops_.fetch_add(1, std::memory_order_relaxed);
                    request_keyframe = true;
                } else {
                    video_retx_.record(rtp::seq_of(pkt), pkt);
                    video_pkts_.fetch_add(1, std::memory_order_relaxed);
                    video_octets_.fetch_add(pkt.size(),
                                            std::memory_order_relaxed);
                    video_last_ts_.store(
                        (static_cast<std::uint32_t>(pkt[4]) << 24) |
                            (pkt[5] << 16) | (pkt[6] << 8) | pkt[7],
                        std::memory_order_relaxed);
                }
            }
            if (request_keyframe && on_keyframe_request) on_keyframe_request();
            if (!sent) break;
        }
    }
}

void RtpServer::send_repairs() {
    // Limit repair priority so repeated NACKs cannot starve current video.
    for (int i = 0; i < 4 && running_; ++i) {
        RepairPacket repair;
        {
            std::lock_guard<std::mutex> lk(send_mu_);
            if (repair_q_.empty()) return;
            repair = std::move(repair_q_.front());
            repair_q_.pop_front();
        }
        if (pacer_ && !pacer_->consume(repair.packet.size(), repair.deadline))
            continue;
        std::scoped_lock lk(peer_mu_, send_mu_);
        if (!peer_streaming_ || repair.generation != send_generation_ ||
            std::chrono::steady_clock::now() >= repair.deadline)
            continue;
        const auto& p = repair.packet;
        if (send_secure(p.data(), p.size()) &&
            repair.video) {
            video_retx_count_.fetch_add(1, std::memory_order_relaxed);
        }
    }
}

void RtpServer::broadcast_audio(std::uint32_t /*channel_id*/,
                                const std::uint8_t* data, std::size_t size,
                                std::int64_t pts_usec) {
    std::lock_guard<std::mutex> lk(peer_mu_);
    if (!peer_streaming_ || !audio_enabled_ || id_ != 0) return;
    std::vector<rtp::Packet> packets;
    audio_packer_->packetize(data, size, pts_usec, packets);
    for (auto& pkt : packets) {
        if (!send_secure(pkt.data(), pkt.size()))
            continue;
        audio_retx_.record(rtp::seq_of(pkt), pkt);
        audio_pkts_.fetch_add(1, std::memory_order_relaxed);
        audio_octets_.fetch_add(pkt.size(), std::memory_order_relaxed);
        audio_last_ts_.store(
            static_cast<std::uint32_t>(
                (static_cast<std::uint64_t>(pts_usec) * rtp::kOpusClockRate) /
                1'000'000),
            std::memory_order_relaxed);
    }
}

void RtpServer::nack_loop() {
    sockaddr_in from{};
    std::vector<std::uint8_t> buf(1 << 16);
    // Timeout so we can re-check running_ between receives.
    timeval tv{0, 200000};
    ::setsockopt(udp_fd_, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    auto last_sr = std::chrono::steady_clock::now();
    std::uint64_t prev_pkts = 0, prev_retx = 0, prev_drops = 0, prev_nacks = 0;
    while (running_) {
        socklen_t flen = sizeof(from);
        ssize_t n = ::recvfrom(udp_fd_, buf.data(), buf.size(), 0,
                               reinterpret_cast<sockaddr*>(&from), &flen);
        if (n <= 0) {
            if (n < 0 &&
                (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
                ;
            else if (!running_)
                break;
        } else {
            handle_rtcp(buf.data(), static_cast<std::size_t>(n), from);
        }
        // Emit RTCP Sender Reports roughly once per second for A/V sync.
        auto now = std::chrono::steady_clock::now();
        if (now - last_sr > std::chrono::seconds(1)) {
            last_sr = now;
            bool streaming;
            {
                std::lock_guard<std::mutex> lk(peer_mu_);
                streaming = peer_streaming_;
            }
            if (streaming) {
                send_sender_report(video_ssrc_, video_pkts_.load(),
                                   video_octets_.load(), video_last_ts_.load());
                if (audio_enabled_ && id_ == 0)
                    send_sender_report(audio_ssrc_, audio_pkts_.load(),
                                       audio_octets_.load(), audio_last_ts_.load());
                // Per-monitor throughput/health, so monitor 0 (physical) and
                // the virtual monitors can be compared at a glance. High retx
                // or loss on the virtual line => the flicker is packet loss on
                // the wire; near-zero on both while it still flickers => the
                // problem is downstream (client decode of a second concurrent
                // stream).
                const std::uint64_t pkts = video_pkts_.load();
                const std::uint64_t retx = video_retx_count_.load();
                const std::uint64_t drops = send_drops_.load();
                std::fprintf(
                    stderr,
                    "[monitor %d stats] %llu pkt/s, %llu retx/s, "
                    "%llu qdrop/s, loss %.1f%%\n",
                    id_, static_cast<unsigned long long>(pkts - prev_pkts),
                    static_cast<unsigned long long>(retx - prev_retx),
                    static_cast<unsigned long long>(drops - prev_drops),
                    static_cast<double>(
                        last_loss_.load(std::memory_order_relaxed)) *
                        100.0);
                const auto requested = video_nack_requests_ - prev_nacks;
                const float repair_fraction = std::min(
                    1.0f, static_cast<float>(requested) /
                              std::max<std::uint64_t>(1, pkts - prev_pkts));
                float pressure = std::max(last_loss_.load(), repair_fraction);
                if (drops > prev_drops) pressure = std::max(pressure, 0.1f);
                if (on_loss_report &&
                    (now - last_rr_ < std::chrono::seconds(2) ||
                     requested > 0 || drops > prev_drops)) {
                    on_loss_report(pressure);
                }
                prev_nacks = video_nack_requests_;
                prev_pkts = pkts;
                prev_retx = retx;
                prev_drops = drops;
            }
        }
    }
}

void RtpServer::handle_rtcp(const std::uint8_t* data, std::size_t size,
                            const sockaddr_in& from) {
    std::vector<std::uint8_t> plain;
    {
        std::lock_guard<std::mutex> lk(peer_mu_);
        if (!peer_streaming_ || from.sin_family != AF_INET ||
            from.sin_addr.s_addr != peer_udp_.sin_addr.s_addr ||
            from.sin_port != peer_udp_.sin_port)
            return;
        const auto now = std::chrono::steady_clock::now();
        if (now-rtcp_window_ >= std::chrono::seconds(1)) { rtcp_window_=now; rtcp_count_=0; }
        if (++rtcp_count_ > 200 || !media_receive_.open(data,size,plain)) return;
    }
    auto now = std::chrono::steady_clock::now();
    data=plain.data(); size=plain.size();
    // Minimal RTCP feedback parser (RFC 4585). We service NACK (RTPFB, PT=205,
    // FMT=1) and PLI (PSFB, PT=206, FMT=1).
    if (size < 8) return;
    const std::uint8_t b0 = data[0];
    if ((b0 & 0xC0) != 0x80) return;  // V must be 2
    const std::uint8_t fmt = b0 & 0x1F;
    const std::uint8_t pt = data[1];
    const std::uint16_t len16 =
        (static_cast<std::uint16_t>(data[2]) << 8) | data[3];
    const std::size_t pkt_len =
        static_cast<std::size_t>(len16 + 1) * 4;  // 32-bit words
    if (pkt_len != size || pkt_len < 8 || (b0 & 0x20)) return;

    if (pt == 206 && fmt == 1) {
        if (pkt_len != 12) return;
        const std::uint32_t media_ssrc = (std::uint32_t(data[8]) << 24) | (std::uint32_t(data[9]) << 16) | (std::uint32_t(data[10]) << 8) | data[11];
        if (media_ssrc != video_ssrc_ || now-last_pli_ < std::chrono::milliseconds(250)) return;
        last_pli_=now;
        // PLI — Picture Loss Indication. Force a keyframe on the next frame.
        if (on_keyframe_request) {
            std::fprintf(stderr, "[rtp] PLI received — requesting keyframe\n");
            on_keyframe_request();
        }
        return;
    }

    if (pt == 205 && fmt == 1) {
        // NACK. media SSRC at offset 8 selects the retransmit buffer.
        if (pkt_len < 16) return;  // header (12) + at least one FCI (4)
        std::uint32_t media_ssrc = (static_cast<std::uint32_t>(data[8]) << 24) |
                                   (data[9] << 16) | (data[10] << 8) | data[11];
        rtp::RetransmitBuffer* buf = nullptr;
        const bool video = media_ssrc == video_ssrc_;
        if (video)
            buf = &video_retx_;
        else if (media_ssrc == audio_ssrc_)
            buf = &audio_retx_;
        if (!buf) return;

        for (std::size_t off = 12; off + 4 <= pkt_len; off += 4) {
            std::uint16_t pid = (data[off] << 8) | data[off + 1];
            std::uint16_t blp = (data[off + 2] << 8) | data[off + 3];
            auto retransmit = [&](std::uint16_t seq) {
                if (video) ++video_nack_requests_;
                std::scoped_lock lk(peer_mu_, send_mu_);
                if (!peer_streaming_ || repair_q_.size() >= 64) return;
                for (const auto& queued : repair_q_)
                    if (queued.video == video &&
                        rtp::seq_of(queued.packet) == seq)
                        return;
                rtp::Packet p;
                std::chrono::steady_clock::time_point sent_at;
                const auto now = std::chrono::steady_clock::now();
                if (buf->get(seq, p, &sent_at) && now - sent_at < kMaxSendAge) {
                    repair_q_.push_back(
                        {std::move(p),
                         std::min(sent_at + kMaxSendAge,
                                  now + std::chrono::milliseconds(20)),
                         send_generation_, video});
                    send_cv_.notify_one();
                }
            };
            retransmit(pid);
            for (int i = 0; i < 16; ++i) {
                if (blp & (1u << i)) retransmit(pid + 1 + i);
            }
        }
        return;
    }

    if (pt == 201) {
        // Receiver Report. Scan the report blocks for our video SSRC and
        // surface fraction-lost to the bitrate controller.
        const std::uint8_t rc = b0 & 0x1F;
        if (pkt_len != 8 + std::size_t(rc) * 24) return;
        std::size_t off = 8;  // header + reporter SSRC
        for (std::uint8_t i = 0; i < rc && off + 24 <= pkt_len;
             ++i, off += 24) {
            std::uint32_t block_ssrc =
                (static_cast<std::uint32_t>(data[off]) << 24) |
                (data[off + 1] << 16) | (data[off + 2] << 8) | data[off + 3];
            if (block_ssrc != video_ssrc_) continue;
            const float fraction_lost =
                static_cast<float>(data[off + 4]) / 256.0f;
            last_loss_.store(fraction_lost, std::memory_order_relaxed);
            last_rr_ = std::chrono::steady_clock::now();
        }
        return;
    }
}

void RtpServer::send_sender_report(std::uint32_t ssrc, std::uint64_t packets,
                                   std::uint64_t octets,
                                   std::uint32_t last_rtp_ts) {
    std::lock_guard<std::mutex> lk(peer_mu_);
    if (!peer_streaming_) return;
    // NTP timestamp (wall clock since 1900 epoch).
    auto now = std::chrono::system_clock::now().time_since_epoch();
    auto secs = std::chrono::duration_cast<std::chrono::seconds>(now).count();
    auto ns =
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count() %
        1'000'000'000;
    std::uint32_t ntp_msw = static_cast<std::uint32_t>(secs) + 2'208'988'800u;
    std::uint32_t ntp_lsw = static_cast<std::uint32_t>(
        static_cast<std::uint64_t>(ns) * 4'294'967'296ull / 1'000'000'000ull);

    std::uint8_t sr[28];
    sr[0] = 0x80;  // V=2, P=0, RC=0
    sr[1] = 200;   // PT=SR
    sr[2] = 0x00;  // length = 6 words (28 bytes)
    sr[3] = 0x06;
    auto put32 = [&](std::size_t off, std::uint32_t v) {
        sr[off] = (v >> 24) & 0xFF;
        sr[off + 1] = (v >> 16) & 0xFF;
        sr[off + 2] = (v >> 8) & 0xFF;
        sr[off + 3] = v & 0xFF;
    };
    put32(4, ssrc);
    put32(8, ntp_msw);
    put32(12, ntp_lsw);
    put32(16, last_rtp_ts);
    put32(20, static_cast<std::uint32_t>(packets));
    put32(24, static_cast<std::uint32_t>(octets));
    send_secure(sr, sizeof(sr));
}

}  // namespace metashare
