// MetaShare streamer CLI.
//
// Pipeline (per monitor):
//   FrameSource -> Encoder (HEVC HW, H.264 SW fallback) -> RtpServer (UDP)
// Multiple monitors run as parallel pipelines, each on its own signaling port
// (base, base+1, …) and its own capture thread.
// DiscoveryResponder (UDP) lets clients find us with no config.

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <poll.h>

extern "C" {
#include <libavutil/frame.h>
}

#include "app_support.hpp"
#include "secure_transport.hpp"
#include "discovery.hpp"
#include "encoder.hpp"
#include "frame_source.hpp"
#include "input_event.hpp"
#include "input_sink.hpp"
#include "pacer.hpp"
#include "rtp_server.hpp"
#include "signaling.hpp"

#include <mutex>

#ifdef METASHARE_HAVE_PORTAL
#include "portal_pipewire_source.hpp"
#endif

#ifdef METASHARE_HAVE_MUTTER
#include "mutter_screencast.hpp"
#include "mutter_virtual_source.hpp"
#endif

#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
#include "audio_encoder.hpp"
#include "audio_source.hpp"
#include "pipewire_audio_source.hpp"
#endif

#include "test_pattern_source.hpp"
#include "test_tone_source.hpp"

using namespace metashare;

namespace {
// Lock-free atomics may be used in a signal handler and are also safe across
// capture, audio and main threads. volatile sig_atomic_t alone is not.
static_assert(std::atomic<bool>::is_always_lock_free);
std::atomic<bool> g_stop{false};
std::atomic<bool> g_input_gate{true};
std::atomic<bool> g_failed{false};
void on_signal(int sig) {
    if (sig == SIGUSR1) g_input_gate = 0;
    else if (sig == SIGUSR2) g_input_gate = 1;
    else g_stop = 1;
}

// Only inherited, private local IPC can open/approve a numeric comparison.
// Terminal-only runs retain manual fingerprint/token pairing.
class PairingControl {
    std::shared_ptr<security::PairingGate> gate_;
    std::atomic<bool> running_{false};
    std::thread thread_;
    int fd_=-1;
  public:
    ~PairingControl() {
        running_=false;
        if(thread_.joinable()) thread_.join();
        if(gate_) gate_->close();
        if(fd_>=0) ::close(fd_);
    }
    bool start(int fd,int status_fd,std::shared_ptr<security::PairingGate> gate,std::string& error) {
        struct stat st{}; const int flags=::fcntl(fd,F_GETFL);
        if(fd==status_fd || flags<0 || (flags&O_ACCMODE)!=O_RDONLY || ::fstat(fd,&st)<0 ||
           !S_ISFIFO(st.st_mode) || st.st_uid!=::getuid() || (st.st_mode&0077)!=0 ||
           ::fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0 || ::fcntl(fd,F_SETFD,FD_CLOEXEC)<0) {
            error="Control fd must be a private read-only pipe"; return false;
        }
        fd_=fd; gate_=std::move(gate); running_=true;
        thread_=std::thread([this] {
            std::string pending;
            while(running_) {
                gate_->tick();
                pollfd p{fd_,POLLIN,0}; const int ready=::poll(&p,1,100);
                if(ready<0) { if(errno==EINTR) continue; break; }
                if(!ready) continue;
                char bytes[256]; const auto n=::read(fd_,bytes,sizeof bytes);
                if(n<0 && (errno==EINTR || errno==EAGAIN)) continue;
                if(n<=0) break;
                for(ssize_t i=0;i<n;++i) {
                    const char c=bytes[i];
                    if(c=='\n') {
                        if(pending=="PAIR_OPEN") gate_->arm();
                        else if(pending=="PAIR_CLOSE") gate_->close();
                        else if(pending.size()==44 && (pending.starts_with("PAIR_ACCEPT ") || pending.starts_with("PAIR_REJECT "))) {
                            std::array<std::uint8_t,16> id{};
                            if(!security::unhex(std::string_view(pending).substr(12),id.data(),id.size())) { running_=false; break; }
                            gate_->decide(std::string_view(pending).substr(12),pending.starts_with("PAIR_ACCEPT "));
                        } else { running_=false; break; }
                        pending.clear();
                    } else if(c<32 || c>126 || pending.size()>=80) { running_=false; break; }
                    else pending+=c;
                }
            }
            gate_->close();
        });
        return true;
    }
};

struct Options {
    std::string source =
#if defined(METASHARE_HAVE_PORTAL)
        "portal";
#else
        "test";
#endif
    int width = 1920;
    int height = 1080;
    // Default matches the Quest's 72 Hz refresh so the client's composite-to-
    // vsync step lands on a whole frame instead of beating against a 60 Hz
    // grid.
    int fps = 72;
    // Ceiling for the loss-based bitrate controller. 8 Mbps is comfortable
    // for 1080p60 HEVC and leaves WiFi headroom for multiple monitors.
    int bitrate_kbps = 8000;
    std::string codec = "hevc";
    bool hardware = true;
    std::uint16_t port = signal::kDefaultSignalingPort;
    bool discovery = true;
    int monitors = 1;
    // Comma-separated list of audio channels to stream. "none" disables audio
    // entirely; recognised names are "system" (desktop output loopback),
    // "mic" (host microphone), "test" (synthetic 440 Hz tone). Defaults to
    // "system" when audio is built in and the source is real (i.e. not
    // the test pattern source); empty when the audio TU isn't linked.
    std::string audio = "";
    int audio_bitrate_kbps = 96;
    // Remote input (Quest pointer/keyboard -> host). Portal capture sessions
    // are then created via the RemoteDesktop portal; if the desktop refuses,
    // the source falls back to capture-only by itself.
    bool input = false;
    int status_fd = -1;
    int control_fd = -1;
    bool validate_only = false;
};

// Wire-level channel ids (kept as bare constants; they only select which audio
// capture pipeline feeds the single audio SSRC on the raw-RTP transport).
constexpr std::uint32_t kChannelAudioSystem = 1;
constexpr std::uint32_t kChannelAudioMic = 2;

void usage(const char* argv0) {
    std::fprintf(stderr,
      "metuxa 0.3.0 - protected desktop streaming for Quest\n\n"
      "Usage: %s [options]\n"
      "  --source <portal|window|mutter|test>   desktop, single window, virtual or test\n"
      "  --monitors <1..3>                    one physical + optional virtual displays\n"
      "  --width <320..7680> --height <240..4320>  even test/virtual dimensions\n"
      "  --fps <15..120>                      default 72\n"
      "  --bitrate <1000..100000>              encoder ceiling kbps, default 8000\n"
      "  --codec <hevc|h264> --no-hw           codec and software fallback\n"
      "  --port <1024..65533>                 TLS base port, default 7878\n"
      "  --no-discovery                      disable UDP discovery 7877\n"
      "  --audio <none|system|test>            default system (test source: none)\n"
      "  --audio-bitrate <16..256>             Opus kbps, default 96\n"
      "  --input / --no-input                 request remote control / view-only (default)\n"
      "  --status-fd <N>                      private JSON status and pairing pipe\n"
      "  --control-fd <N>                     private pairing approval pipe\n"
      "  --validate                          validate options without starting anything\n"
      "  --version / --help\n\n"
      "TLS control and authenticated encrypted media are mandatory. Start creates\n"
      "fresh credentials; compare the fingerprint and enter the pairing code in Quest.\n"
      "No microphone capture. Window capture is view-only and supports one panel.\n"
      "SIGUSR1 blocks remote input; SIGUSR2 restores only previously granted input.\n",
      argv0);
}

bool parse_args(int argc, char** argv, Options& o) {
    for(int i=1;i<argc;++i){
        const std::string a=argv[i];
        auto val=[&](int& dst){return i+1<argc && app::integer(argv[++i],dst);};
        auto str=[&](std::string& dst){if(i+1>=argc)return false;dst=argv[++i];return true;};
        if(a=="--help" || a=="-h"){usage(argv[0]);std::exit(0);}
        else if(a=="--version"){std::puts("metuxa 0.3.0 (MetaShare ed13beb lineage, protocol 5)");std::exit(0);}
        else if(a=="--validate")o.validate_only=true;
        else if(a=="--source"){if(!str(o.source))return false;}
        else if(a=="--codec"){if(!str(o.codec))return false;}
        else if(a=="--audio"){if(!str(o.audio))return false;}
        else if(a=="--monitors"){if(!val(o.monitors))return false;}
        else if(a=="--width"){if(!val(o.width))return false;}
        else if(a=="--height"){if(!val(o.height))return false;}
        else if(a=="--fps"){if(!val(o.fps))return false;}
        else if(a=="--bitrate"){if(!val(o.bitrate_kbps))return false;}
        else if(a=="--audio-bitrate"){if(!val(o.audio_bitrate_kbps))return false;}
        else if(a=="--status-fd"){if(!val(o.status_fd) || o.status_fd<3)return false;}
        else if(a=="--control-fd"){if(!val(o.control_fd) || o.control_fd<3)return false;}
        else if(a=="--port"){int n;if(!val(n) || n<1024 || n>65533)return false;o.port=static_cast<std::uint16_t>(n);}
        else if(a=="--no-hw")o.hardware=false;
        else if(a=="--no-discovery")o.discovery=false;
        else if(a=="--input")o.input=true;
        else if(a=="--no-input")o.input=false;
        else {std::fprintf(stderr,"Unknown or incomplete option: %s\n",a.c_str());return false;}
    }
    if(o.source!="test" && o.source!="portal" && o.source!="window" && o.source!="mutter")return false;
#ifndef METASHARE_HAVE_PORTAL
    if(o.source=="portal" || o.source=="window" || o.source=="mutter"){std::fprintf(stderr,"Portal capture is not built in.\n");return false;}
#endif
    if(o.codec!="hevc" && o.codec!="h264")return false;
    if(o.monitors<1 || o.monitors>3 || o.port+o.monitors-1>65535 ||
       o.width<320 || o.width>7680 || o.width%2 || o.height<240 || o.height>4320 || o.height%2 ||
       o.fps<15 || o.fps>120 || o.bitrate_kbps<1000 || o.bitrate_kbps>100000 ||
       o.audio_bitrate_kbps<16 || o.audio_bitrate_kbps>256)return false;
    if(o.source=="window" && (o.monitors!=1 || o.input)){std::fprintf(stderr,"Window capture requires --monitors 1 --no-input.\n");return false;}
    if(o.audio.empty())o.audio=o.source=="test"?"none":"system";
    if(o.audio!="none" && o.audio!="system" && o.audio!="test"){std::fprintf(stderr,"Only none, system or test audio is supported; microphone capture is not implemented.\n");return false;}
    return true;
}

// One complete capture→encode→serve pipeline per monitor.
struct Pipeline {
    int index = 0;
    std::unique_ptr<FrameSource> source;
    std::unique_ptr<Encoder> encoder;
    EncoderConfig encoder_config;
    // Recursive because broadcast_video can request an IDR synchronously.
    std::recursive_mutex encoder_mu;
    std::uint64_t display_generation = 0;
    std::unique_ptr<RtpServer> server;
    std::shared_ptr<app::InputState> input_state;
    bool input_available = false;
    std::thread thread;
    std::atomic<bool> running{false};

    // Loss-based bitrate adaptation, driven by the client's RTCP receiver
    // reports (runs on the server's RTCP thread).
    std::mutex abr_mu;
    int abr_kbps = 0;  // current encoder target
    std::chrono::steady_clock::time_point abr_last_down{};
    std::chrono::steady_clock::time_point abr_clean_since{};
    bool set_orientation(bool portrait) {
        std::lock_guard lock(encoder_mu);
        if (!running || !source || source->display_source() != display::Source::Virtual ||
            !source->can_set_orientation()) return false;
        const auto before = source->format();
        int width = 0, height = 0;
        if (!display::dimensions(before.width, before.height, portrait, width, height)) return false;
        if (width == before.width && height == before.height) return true;
        auto config = encoder_config;
        config.width = width;
        config.height = height;
        config.bitrate_kbps = encoder->bitrate_kbps();
        auto replacement = std::make_unique<Encoder>();
        std::string error;
        if (!replacement->open(config, error)) {
            std::fprintf(stderr, "[monitor %d] orientation encoder unavailable: %s\n", index, error.c_str());
            return false;
        }
        if (!source->resize_desktop(width, height, error)) {
            std::fprintf(stderr, "[monitor %d] orientation failed: %s\n", index, error.c_str());
            // A rejected negotiation may still have reached the compositor.
            // Restore this same virtual source and verify a frame at its old size.
            if (!source->resize_desktop(before.width, before.height, error)) {
                std::fprintf(stderr, "[monitor %d] virtual display restore failed; restart sharing: %s\n", index, error.c_str());
                server->fail_display();
                running = false; // Fail this monitor closed; leave other monitors alone.
            }
            ++display_generation;
            return false;
        }
        encoder.swap(replacement);
        encoder_config = config;
        server->update_display_format({width, height, before.fps_num, before.fps_den, encoder->codec()});
        ++display_generation;
        return true;
    }

    ~Pipeline() {
        running=false;
        if(thread.joinable())thread.join();
        if(server)server->stop();
        if(input_state)input_state->release();
        input_state.reset();
        if(source)source->stop();
    }
};

// Bitrate adaptation bounds/steps. On loss we cut hard (multiplicative
// decrease), on a sustained clean link we creep back up toward the configured
// maximum — classic AIMD, tuned slow so it never fights the jitter buffer.
constexpr int kAbrMinKbps = 2000;
constexpr float kAbrCutFactor = 0.6f;
constexpr float kAbrGrowFactor = 1.08f;
constexpr auto kAbrDownCooldown = std::chrono::seconds(2);
constexpr auto kAbrGrowAfterClean = std::chrono::seconds(4);

#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
// One capture→encode pipeline per audio channel. Encoded packets are fanned
// out only to the primary video pipeline's NetServer so a client that connected to any
// monitor port gets the same audio. Bandwidth cost is tiny (~96 kbps/channel).
struct AudioChannel {
    std::uint32_t channel_id = 0;
    std::unique_ptr<AudioSource> source;
    std::unique_ptr<AudioEncoder> encoder;
    std::thread thread;
    std::atomic<bool> running{false};
    ~AudioChannel() {
        running = false;
        // Audio sources must unblock next_chunk before the reader can join.
        // Their loaned PCM remains valid until the thread has finished.
        if (source) source->stop();
        if (thread.joinable()) thread.join();
    }
};

// Parse the comma-separated --audio list into channel ids. Recognised tokens:
// "none", "system", "mic", "test". Unknown tokens are an error.
std::vector<std::uint32_t> parse_audio_list(const std::string& s,
                                            std::string& err) {
    std::vector<std::uint32_t> out;
    std::stringstream ss(s);
    std::string tok;
    while (std::getline(ss, tok, ',')) {
        // Trim whitespace.
        auto p = tok.find_first_not_of(" \t");
        if (p != std::string::npos) tok.erase(0, p);
        auto q = tok.find_last_not_of(" \t");
        if (q != std::string::npos) tok.erase(q + 1);
        if (tok.empty()) continue;
        if (tok == "none") {
            out.clear();
            return out;
        }
        // Map the token to its wire channel id. "test" re-uses the system
        // channel slot so a client expecting desktop audio picks the synthetic
        // tone up transparently; there is no separate test channel on the wire.
        std::uint32_t id = 0;
        bool recognized = true;
        if (tok == "system" || tok == "test") {
            id = kChannelAudioSystem;
        } else if (tok == "mic") {
            id = kChannelAudioMic;
        } else {
            recognized = false;
        }
        if (!recognized) {
            err = "unknown --audio token: " + tok;
            return {};
        }
        out.push_back(id);
    }
    // De-dup (e.g. "system,system").
    std::sort(out.begin(), out.end());
    out.erase(std::unique(out.begin(), out.end()), out.end());
    return out;
}
#endif

std::unique_ptr<FrameSource>
make_source(const Options& o, int monitor_index,
#ifdef METASHARE_HAVE_MUTTER
            MutterScreenCastSession* mutter_session,
#else
            void* /*mutter_session*/,
#endif
            std::string& err) {
    if (o.source == "test") {
        SourceFormat f{o.width, o.height, o.fps, 1};
        return std::make_unique<TestPatternSource>(f, monitor_index);
    }
    if (o.source == "portal" || o.source == "window") {
#ifdef METASHARE_HAVE_PORTAL
        // Monitor 0: the user's real physical display (source_type=MONITOR
        // brings up the standard GNOME picker once for the actual screen).
        // Monitors 1..N-1: virtual monitors (source_type=VIRTUAL) that the
        // client only opens when selected in the app's monitor menu.
        PortalOptions opts;
        opts.source_types =
            (monitor_index == 0) ? (o.source == "window" ? 2u : 1u) : 4u;  // MONITOR : VIRTUAL
        opts.fps_hint = o.fps;
        opts.enable_input = o.input;
        opts.cancel_requested = [] { return g_stop.load(); };
        return std::make_unique<PortalPipeWireSource>(opts);
#else
        err = "this build has no portal support; rebuild with portal deps or "
              "use --source test";
        return nullptr;
#endif
    }
    if (o.source == "mutter") {
        // Monitor 0: physical monitor, captured via the portal (so the user
        // gets the standard GNOME picker once for the real display).
        if (monitor_index == 0) {
#ifdef METASHARE_HAVE_PORTAL
            PortalOptions opts;
            opts.source_types = 1;  // MONITOR
            opts.fps_hint = o.fps;
            opts.enable_input = o.input;
            opts.cancel_requested = [] { return g_stop.load(); };
            return std::make_unique<PortalPipeWireSource>(opts);
#else
            err = "mutter mode needs the portal source for monitor 0; "
                  "rebuild with portal support";
            return nullptr;
#endif
        }
        // Monitors 1..N-1: virtual monitors. If the Mutter direct session is
        // active (METASHARE_MUTTER_DIRECT env set + it actually started), use
        // MutterVirtualSource bound to it. Otherwise fall back to the portal's
        // VIRTUAL source type, which creates a real virtual monitor via
        // Mutter's RecordVirtual under the hood.
#ifdef METASHARE_HAVE_MUTTER
        if (mutter_session) {
            return std::make_unique<MutterVirtualSource>(
                *mutter_session, monitor_index - 1, o.fps);
        }
#endif
#ifdef METASHARE_HAVE_PORTAL
        {
            PortalOptions opts;
            opts.source_types = 4;  // VIRTUAL
            opts.fps_hint = o.fps;
            opts.enable_input = o.input;
            opts.cancel_requested = [] { return g_stop.load(); };
            return std::make_unique<PortalPipeWireSource>(opts);
        }
#else
        err = "mutter mode needs portal or mutter support for virtual monitors";
        return nullptr;
#endif
    }
    err = "unknown source: " + o.source;
    return nullptr;
}
}  // namespace

int main(int argc, char** argv) {
    Options opt;
    if (!parse_args(argc, argv, opt)) {
        usage(argv[0]);
        return 2;
    }

    if (opt.validate_only) { std::puts("Options valid; no capture, network, or input started."); return 0; }
    std::signal(SIGPIPE, SIG_IGN);
    std::string status_err;
    app::Status status;
    if(!status.init(opt.status_fd,status_err)){std::fprintf(stderr,"%s\n",status_err.c_str());return 2;}
    status.state("starting", "Initializing protected session");
    app::InstanceLock instance;
    if(!instance.acquire(status_err)){status.state("error",status_err);std::fprintf(stderr,"%s\n",status_err.c_str());return 1;}
    auto credentials=security::ServerCredentials::create(status_err);
    if(!credentials){status.state("error","Unable to create TLS identity");std::fprintf(stderr,"TLS initialization failed: %s\n",status_err.c_str());return 1;}
    if(!status.emit("{\"type\":\"security\",\"fingerprint\":"+app::quote(credentials->fingerprint())+
                    ",\"pairing_code\":"+app::quote(credentials->pairing_code())+"}")){
        std::fprintf(stderr,"Private status consumer unavailable; refusing to start without pairing information.\n");return 1;
    }
    auto pairing_gate=std::make_shared<security::PairingGate>([&status](const std::string& event) { return status.emit(event); });
    PairingControl pairing_control;
    if(opt.control_fd>=0) {
        if(!pairing_control.start(opt.control_fd,opt.status_fd,pairing_gate,status_err)) {
            status.state("error",status_err); std::fprintf(stderr,"%s\n",status_err.c_str()); return 2;
        }
        credentials->set_pairing_gate(pairing_gate);
    }
    std::signal(SIGUSR1,on_signal);
    std::signal(SIGUSR2,on_signal);
    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);

    // The Mutter session must outlive every MutterVirtualSource that references
    // it, so we declare it before the pipelines vector. C++ destroys locals in
    // reverse order, so this is torn down only after all pipelines are gone.
    // Normal sessions use the desktop portal, including its VIRTUAL source.
    // The direct Mutter API remains explicitly opt-in and experimental. Its
    // session/resize lifecycle is tested in an isolated headless compositor;
    // that does not establish compatibility with the user's GNOME session.
    std::string err;
#ifdef METASHARE_HAVE_MUTTER
    std::unique_ptr<MutterScreenCastSession> mutter_session;
    if (opt.source == "mutter" && opt.monitors > 1 &&
        std::getenv("METASHARE_MUTTER_DIRECT")) {
        std::fprintf(
            stderr,
            "[mutter] attempting direct Mutter ScreenCast (experimental;\n"
            "  tested on isolated headless Mutter; normal sessions use the "
            "desktop portal).\n");
        mutter_session = std::make_unique<MutterScreenCastSession>();
        if (!mutter_session->open(err)) {
            std::fprintf(
                stderr,
                "[mutter] session open failed: %s — falling back to portal.\n",
                err.c_str());
            mutter_session.reset();
        } else {
            for (int i = 1; i < opt.monitors; ++i) {
                const int idx = mutter_session->add_virtual_monitor(
                    opt.width, opt.height, err);
                if (idx < 0) {
                    std::fprintf(stderr,
                                 "[mutter] add_virtual_monitor(%d) failed: %s\n"
                                 "  — falling back to portal.\n",
                                 i, err.c_str());
                    mutter_session.reset();
                    break;
                }
            }
            if (mutter_session) {
                if (!mutter_session->start(err)) {
                    std::fprintf(stderr,
                                 "[mutter] session start failed: %s\n"
                                 "  — falling back to portal.\n",
                                 err.c_str());
                    mutter_session.reset();
                } else {
                    std::fprintf(
                        stderr,
                        "[mutter] %d virtual monitor(s) ready via direct API\n",
                        opt.monitors - 1);
                }
            }
        }
    }
#endif

    // Host-wide send pacer shared by every pipeline (declared before the
    // pipelines so it outlives their server threads).
    Pacer pacer;

    std::vector<std::unique_ptr<Pipeline>> pipelines;

#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
    // Resolve the --audio list up front. Audio starts *after* the video
    // pipelines are up so its fan-out list is non-empty when its threads
    // come online.
    std::string audio_err;
    std::vector<std::uint32_t> audio_channels =
        parse_audio_list(opt.audio, audio_err);
    if (!audio_err.empty()) {
        std::fprintf(stderr, "audio: %s\n", audio_err.c_str());
        return 2;
    }
    bool want_sys = false, want_test_tone = false;
    for (std::uint32_t id : audio_channels) {
        if (id == kChannelAudioSystem) want_sys = true;
    }
    if (opt.audio.find("test") != std::string::npos) {
        want_test_tone = true;
        want_sys = true;  // test tone replaces system audio
    }
    const AudioFormat audio_fmt{/*sample_rate=*/48000, /*channels=*/2};
#else
    if (!opt.audio.empty() && opt.audio != "none") {
        std::fprintf(stderr,
                     "audio: this build has no PipeWire support; rebuild with "
                     "-Dportal=enabled or pass --audio none\n");
        return 2;
    }
#endif

    for (int i = 0; i < opt.monitors; ++i) {
        if(g_stop)break;
        status.state(opt.source=="test"?"starting":"requesting_access", "Preparing monitor "+std::to_string(i+1));
        auto p = std::make_unique<Pipeline>();
        p->index = i;

        // --- source ---
#ifdef METASHARE_HAVE_MUTTER
        p->source = make_source(opt, i, mutter_session.get(), err);
#else
        p->source = make_source(opt, i, nullptr, err);
#endif
        if (!p->source) {
            std::fprintf(stderr, "[monitor %d] error: %s\n", i, err.c_str());
            status.state("error",err); return 1;
        }
        if (!p->source->start(err)) {
            if (g_stop) { status.state("stopped", "Session start cancelled"); return 0; }
            std::fprintf(stderr, "[monitor %d] source start failed: %s\n", i,
                         err.c_str());
            status.state("error",err); return 1;
        }
        const SourceFormat fmt = p->source->format();
        std::fprintf(stderr, "[monitor %d] capture %dx%d @ %d/%d fps\n", i,
                     fmt.width, fmt.height, fmt.fps_num, fmt.fps_den);

        // --- encoder ---
        p->encoder = std::make_unique<Encoder>();
        EncoderConfig ecfg;
        ecfg.width = fmt.width;
        ecfg.height = fmt.height;
        ecfg.fps_num = fmt.fps_num;
        ecfg.fps_den = fmt.fps_den;
        ecfg.bitrate_kbps = opt.bitrate_kbps;
        ecfg.prefer_hardware = opt.hardware;
        ecfg.preferred_codec =
            opt.codec == "hevc" ? proto::Codec::kH265 : proto::Codec::kH264;
        if (!p->encoder->open(ecfg, err)) {
            std::fprintf(stderr, "[monitor %d] encoder open failed: %s\n", i,
                         err.c_str());
            status.state("error",err); return 1;
        }
        p->encoder_config = ecfg;
        const bool is_h265 = (p->encoder->codec() == proto::Codec::kH265);
        std::fprintf(stderr, "[monitor %d] encoder: %s (%s)%s\n", i,
                     p->encoder->codec_name(), is_h265 ? "HEVC" : "H.264",
                     p->encoder->using_hardware() ? " hw" : " sw");

        // --- RTP server (signaling + raw UDP media transport) ---
        p->server = std::make_unique<RtpServer>();
        p->server->set_id(i);
        p->server->set_monitor_count(opt.monitors);
        p->server->set_security(credentials);
        p->server->set_audio_enabled(false);
        VideoFormat vfmt;
        vfmt.width = fmt.width;
        vfmt.height = fmt.height;
        vfmt.fps_num = fmt.fps_num;
        vfmt.fps_den = fmt.fps_den;
        vfmt.codec = is_h265 ? proto::Codec::kH265 : proto::Codec::kH264;
        p->server->set_video_format(vfmt);
        p->server->set_display_source(p->source->display_source(), p->source->can_set_orientation());
        p->server->on_display_orientation = [raw = p.get(), &status](bool portrait) {
            std::lock_guard lock(raw->encoder_mu);
            if (!raw->set_orientation(portrait)) return false;
            const auto& config = raw->encoder_config;
            status.emit("{\"type\":\"stream\",\"monitor\":" + std::to_string(raw->index) +
                        ",\"codec\":" + app::quote(raw->encoder->codec() == proto::Codec::kH265 ? "hevc" : "h264") +
                        ",\"encoder\":" + app::quote(raw->encoder->codec_name()) +
                        ",\"width\":" + std::to_string(config.width) + ",\"height\":" + std::to_string(config.height) +
                        ",\"fps\":" + std::to_string(config.fps_num / std::max(1, config.fps_den)) + "}");
            return true;
        };
#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
        // Raw-RTP transport carries a single audio stream; it lives on
        // monitor 0's server (audio is system-wide, so one feed per machine).
        if (i == 0) {
            p->server->set_audio_format(audio_fmt.sample_rate,
                                        audio_fmt.channels);
        }
#endif
        // Service client PLI / keyframe requests by forcing the encoder's next
        // frame to be a keyframe.
        p->server->on_keyframe_request = [raw = p.get()] {
            std::lock_guard lock(raw->encoder_mu);
            if (raw->encoder) raw->encoder->force_keyframe();
        };

        // Remote input: route this monitor's INPUT events into its own capture
        // session, so pointer coordinates land on the surface the client sees.
        // Sources without an injection backend leave on_input unset and the
        // client's input is dropped. (The sink stays valid until source->stop()
        // and guards itself against post-stop calls.)
        if(opt.input) {
            if(InputSink* sink=p->source->input_sink()){
                p->input_available=true;
                p->input_state=std::make_shared<app::InputState>(sink);
                p->server->on_input=[state=p->input_state](const input::Event& e){state->accept(e);};
                p->server->on_input_release=[state=p->input_state]{state->release();};
            }
        }
        p->server->set_input_enabled(p->input_available);
        status.emit("{\"type\":\"capability\",\"monitor\":"+std::to_string(i)+
                    ",\"input_available\":"+(p->input_available?"true":"false")+
                    ",\"input_active\":"+(p->input_available && g_input_gate.load()?"true":"false")+",\"audio_active\":false}");
        status.emit("{\"type\":\"stream\",\"monitor\":"+std::to_string(i)+
                    ",\"codec\":"+app::quote(is_h265?"hevc":"h264")+
                    ",\"encoder\":"+app::quote(p->encoder->codec_name())+
                    ",\"width\":"+std::to_string(fmt.width)+",\"height\":"+std::to_string(fmt.height)+
                    ",\"fps\":"+std::to_string(fmt.fps_num/(fmt.fps_den?fmt.fps_den:1))+"}");

        // Pace all video sends through the shared bucket, and adapt the
        // encoder bitrate to the loss the client reports (RTCP RRs).
        p->server->set_pacer(&pacer);
        p->abr_kbps = opt.bitrate_kbps;
        p->abr_last_down = std::chrono::steady_clock::now();
        p->abr_clean_since = p->abr_last_down;
        p->server->set_pacing_bitrate(opt.bitrate_kbps);
        p->server->on_loss_report = [raw = p.get(),
                                     max_kbps = opt.bitrate_kbps](float lost) {
            const auto now = std::chrono::steady_clock::now();
            std::lock_guard<std::mutex> lk(raw->abr_mu);
            int next = raw->abr_kbps;
            if (lost > 0.02f) {
                raw->abr_clean_since = now;
                if (now - raw->abr_last_down >= kAbrDownCooldown) {
                    next =
                        std::max(std::min(kAbrMinKbps,max_kbps), static_cast<int>(raw->abr_kbps *
                                                               kAbrCutFactor));
                    raw->abr_last_down = now;
                }
            } else if (lost < 0.005f) {
                if (now - raw->abr_clean_since >= kAbrGrowAfterClean &&
                    raw->abr_kbps < max_kbps) {
                    next = std::min(max_kbps, static_cast<int>(raw->abr_kbps *
                                                               kAbrGrowFactor));
                    raw->abr_clean_since = now;
                }
            } else {
                // Moderate loss: hold and reset the clean-link timer.
                raw->abr_clean_since = now;
            }
            if (next != raw->abr_kbps) {
                std::fprintf(stderr,
                             "[monitor %d] bitrate %d -> %d kbps "
                             "(pressure %.1f%%)\n",
                             raw->index, raw->abr_kbps, next,
                             static_cast<double>(lost) * 100.0);
                raw->abr_kbps = next;
                std::lock_guard lock(raw->encoder_mu);
                raw->encoder->set_bitrate(next);
            }
        };
        pipelines.push_back(std::move(p));
    }

    // --- discovery ---
    DiscoveryResponder discovery;
    if (opt.discovery && !g_stop) {
        if (!discovery.start(opt.port, static_cast<std::uint16_t>(opt.monitors),
                             err))
            std::fprintf(stderr, "warning: discovery disabled: %s\n",
                         err.c_str());
        else
            std::fprintf(
                stderr, "[streamer] discovery on udp/%u (%d monitors)\n",
                static_cast<unsigned>(proto::kDiscoveryPort), opt.monitors);
    }

    // --- capture threads ---
    for (auto& p : pipelines) {
        if (g_stop) break;
        p->running = true;
        auto* raw = p.get();
        raw->thread = std::thread([raw] {
            auto sink = [raw](const std::uint8_t* data, std::size_t size,
                              std::int64_t pts_usec, bool key) {
                raw->server->set_pacing_bitrate(raw->encoder->bitrate_kbps());
                raw->server->broadcast_video(data, size, pts_usec, key);
            };
            std::string thread_err;
            // Pointer to the most recent frame. next_frame() guarantees the
            // returned frame stays valid until the next call, so we can hold it
            // across iterations and re-encode it when the source goes idle.
            AVFrame* last = nullptr;
            std::int64_t last_pts = 0;
            std::uint64_t display_generation = 0;

            // Steady send cadence, decoupled from the compositor's delivery.
            // Mutter's virtual monitors are damage-driven: on_process() fires
            // in tight bursts (sub-millisecond spacing) then goes quiet for
            // hundreds of milliseconds, whereas the physical monitor is sampled
            // at a steady vsync. Both used to stamp PTS with
            // steady_clock::now() at delivery time and encode on every buffer,
            // so the bursty virtual cadence flowed straight through to the RTP
            // clock and the client showed it as heavy jitter.
            //
            // We now tick at a steady 1/fps and always encode the most recently
            // captured frame, mirroring TestPatternSource's pacing. Sleeping
            // before each pull means burst frames that arrive between ticks are
            // naturally dropped by the source's double buffer — we always grab
            // the latest on wake, so no intermediate content is missed, only
            // the irregular delivery timing is smoothed away.
            const SourceFormat fmt = raw->source->format();
            // Microseconds per frame, rounded from the source's fps rational.
            // Integer math avoids float-rounding drift and keeps the pacing
            // clock exact.
            const std::int64_t period_usec =
                (1'000'000LL * (fmt.fps_den ? fmt.fps_den : 1) +
                 (fmt.fps_num ? fmt.fps_num : 60) / 2) /
                (fmt.fps_num ? fmt.fps_num : 60);
            const auto period = std::chrono::microseconds(period_usec);
            auto next_tick = std::chrono::steady_clock::now() + period;
            // One-shot startup line so it is unambiguous from stderr that this
            // paced capture loop (not an older build) is the code running.
            std::fprintf(stderr,
                         "[monitor %d] pacer: period=%lldus (%.3f fps)\n",
                         raw->index, static_cast<long long>(period_usec),
                         1'000'000.0 / static_cast<double>(period_usec));

            // Non-blocking pull is what makes the pacing real. latest_frame()
            // returns immediately with either a fresh frame or "nothing new"
            // (in which case we re-encode `last`), so the loop period is set
            // solely by sleep_until(next_tick) — never dragged by the
            // compositor's bursty delivery the way the blocking next_frame()
            // was. Every tick encodes something (fresh or repeated) at an even
            // PTS, which also keeps the link alive and lets a PLI-forced
            // keyframe land within one interval on an otherwise idle monitor.

            while (raw->running && !g_stop) {
                std::this_thread::sleep_until(next_tick);
                next_tick += period;
                auto now = std::chrono::steady_clock::now();
                if (next_tick < now) next_tick = now + period;  // fell behind
                std::lock_guard lock(raw->encoder_mu);
                if (!raw->running) break;
                if (display_generation != raw->display_generation) {
                    last = nullptr;
                    display_generation = raw->display_generation;
                }
                // Poll source health even with no connected client. A revoked
                // portal session must not leave a listening zombie server.
                AVFrame* frame = nullptr;
                std::int64_t pts = 0;
                const int r = raw->source->latest_frame(&frame, pts);
                if (r < 0) {
                    std::fprintf(stderr, "[monitor %d] source ended\n",
                                 raw->index);
                    g_failed=true; g_stop=1; break;
                }
                if (r > 0) last = frame;
                if (!last || raw->server->peer_count() == 0) continue;
                // No video encoding without a client, but keep the latest frame.


                // PTS snapped to absolute period boundaries of the wall clock,
                // so consecutive ticks land on consecutive multiples of
                // period_usec — a perfectly even RTP clock for the client's
                // jitter buffer, regardless of how bursty capture was. `now` is
                // the post-sleep tick time (latest_frame() above doesn't
                // block), so it already sits on the grid.
                const auto now_us =
                    std::chrono::duration_cast<std::chrono::microseconds>(
                        now.time_since_epoch())
                        .count();
                const std::int64_t tick =
                    (now_us + period_usec / 2) / period_usec;
                std::int64_t enc_pts = tick * period_usec;
                if (enc_pts <= last_pts) enc_pts = last_pts + period_usec;
                last_pts = enc_pts;

                if (!raw->encoder->encode(last, enc_pts, sink, thread_err)) {
                    std::fprintf(stderr, "[monitor %d] encode error: %s\n",
                                 raw->index, thread_err.c_str());
                    g_failed=true; g_stop=1; break;
                }
            }
            std::lock_guard lock(raw->encoder_mu);
            raw->encoder->flush(sink);
        });
    }

    std::fprintf(stderr, "[streamer] %d monitor(s) running — Ctrl-C to stop\n",
                 opt.monitors);

#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
    // --- audio threads ---
    // Each audio channel captures → encodes → fans the encoded Opus packet out
    // to *every* video pipeline's NetServer (audio is system-wide, not per
    // monitor, so any client gets it regardless of which monitor port they
    // connected to).
    std::vector<std::unique_ptr<AudioChannel>> audio_pipelines;
    if (want_sys || want_test_tone) {
        // Build the list of (channel_id, source) pairs. The raw-RTP transport
        // carries a single audio stream — only the system/test channel is fed.
        struct AudioSpec {
            std::uint32_t channel_id;
            PipeWireAudioSource::Mode mode;
            bool is_test;
        };
        std::vector<AudioSpec> specs;
        if (want_test_tone) {
            specs.push_back({kChannelAudioSystem, {}, /*is_test=*/true});
        } else {
            for (std::uint32_t id : audio_channels) {
                if (id == kChannelAudioSystem)
                    specs.push_back(
                        {id, PipeWireAudioSource::Mode::kSystemSinkMonitor,
                         /*is_test=*/false});
            }
        }

        for (const auto& spec : specs) {
            if (g_stop) break;
            auto ac = std::make_unique<AudioChannel>();
            ac->channel_id = spec.channel_id;

            if (spec.is_test) {
                ac->source = std::make_unique<TestToneSource>(audio_fmt);
            } else {
                ac->source =
                    std::make_unique<PipeWireAudioSource>(spec.mode, audio_fmt);
            }
            if (!ac->source->start(err)) {
                std::fprintf(stderr,
                             "[audio:%u] source start failed: %s — disabling\n",
                             spec.channel_id, err.c_str());
                err.clear();
                continue;
            }

            ac->encoder = std::make_unique<AudioEncoder>();
            AudioEncoderConfig acfg;
            acfg.format = audio_fmt;
            acfg.bitrate_kbps = opt.audio_bitrate_kbps;
            if (!ac->encoder->open(acfg, err)) {
                std::fprintf(stderr,
                             "[audio:%u] encoder open failed: %s — disabling\n",
                             spec.channel_id, err.c_str());
                err.clear();
                ac->source->stop();
                continue;
            }

            ac->running = true;
            // Send audio only to the primary monitor's RtpServer. Only monitor 0's
            // server has audio configured, but broadcast_audio() is a no-op
            // for servers that never had a client READY on the audio stream.
            std::vector<RtpServer*> sinks;
            sinks.reserve(pipelines.size());
            if(!pipelines.empty()) {
                pipelines[0]->server->set_audio_enabled(true);
                sinks.push_back(pipelines[0]->server.get());
            }
            const std::uint32_t cid = spec.channel_id;
            auto* raw = ac.get();
            ac->thread = std::thread([raw, sinks, cid] {
                auto sink = [raw, sinks, cid](const std::uint8_t* data,
                                              std::size_t size,
                                              std::int64_t pts_usec) {
                    for (RtpServer* s : sinks)
                        s->broadcast_audio(cid, data, size, pts_usec);
                };
                std::string thread_err;
                while (raw->running && !g_stop) {
                    const std::int16_t* pcm = nullptr;
                    std::int64_t pts = 0;
                    int r = raw->source->next_chunk(&pcm, pts);
                    if (r == 0) continue;
                    if (r < 0) {
                        if (raw->running && !g_stop) {
                            std::fprintf(stderr, "[audio:%u] source ended unexpectedly\n", cid);
                            g_failed = true;
                            g_stop = true;
                        }
                        break;
                    }
                    if (!raw->encoder->encode(pcm, r, pts, sink, thread_err)) {
                        std::fprintf(stderr, "[audio:%u] encode error: %s\n",
                                     cid, thread_err.c_str());
                        g_failed = true;
                        g_stop = true;
                        break;
                    }
                }
                raw->running = false;
                raw->encoder->flush(sink);
            });

            const char* kind =
                spec.is_test ? "test-tone"
                : spec.mode == PipeWireAudioSource::Mode::kSystemSinkMonitor
                    ? "system-output"
                    : "microphone";
            std::fprintf(stderr,
                         "[audio:%u] %s capture running (opus %d kbps)\n",
                         spec.channel_id, kind, opt.audio_bitrate_kbps);
            audio_pipelines.push_back(std::move(ac));
        }
    }
#endif

    bool startup_failed=false;
    bool audio_active=false;
#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
    audio_active=!audio_pipelines.empty();
#endif
    if(pipelines.size()!=static_cast<std::size_t>(opt.monitors))g_stop=1;
    bool previous_gate = g_input_gate.load();
    for(auto& p:pipelines){
        if(g_stop)break;
        const bool input_active = previous_gate && p->input_available;
        if (p->input_state) p->input_state->set_allowed(input_active);
        p->server->set_input_enabled(input_active);
        if(!p->server->start(static_cast<std::uint16_t>(opt.port+p->index),err)){
            status.state("error",err); std::fprintf(stderr,"Server start failed: %s\n",err.c_str());
            startup_failed=true;g_stop=1;break;
        }
        status.emit("{\"type\":\"capability\",\"monitor\":"+std::to_string(p->index)+
                    ",\"input_available\":"+(p->input_available?"true":"false")+
                    ",\"input_active\":"+(input_active?"true":"false")+
                    ",\"audio_active\":"+(p->index==0 && audio_active?"true":"false")+"}");
    }
    if(!g_stop)status.emit("{\"state\":\"listening\",\"endpoint\":\"TLS port "+std::to_string(opt.port)+
                          "\",\"message\":\"Ready for paired Quest\"}");
    int previous_peers=0;
    while (!g_stop) {
        int peers=0;
        bool gate=g_input_gate!=0;
        for(auto& p:pipelines){
            peers+=p->server->peer_count();
            const bool available=p->input_available && p->source->input_sink()!=nullptr;
            const bool availability_changed = available != p->input_available;
            if (availability_changed) p->input_available = available;
            if(gate!=previous_gate || availability_changed){
                if(p->input_state)p->input_state->set_allowed(gate && available);
                p->server->set_input_enabled(gate && available);
            }
            if(gate!=previous_gate || availability_changed)status.emit("{\"type\":\"capability\",\"monitor\":"+std::to_string(p->index)+
                ",\"input_available\":"+(available?"true":"false")+
                ",\"input_active\":"+(gate&&available?"true":"false")+
                ",\"audio_active\":"+(p->index==0&&audio_active?"true":"false")+"}");
        }
        if(peers!=previous_peers){status.state(peers?"connected":"listening",peers?"Protected Quest session connected":"Waiting for paired Quest");previous_peers=peers;}
        previous_gate=gate;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    std::fprintf(stderr, "\n[streamer] shutting down\n");
    discovery.stop();

#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
    for (auto& a : audio_pipelines) {
        a->running = false;
        a->source->stop();
        if (a->thread.joinable()) a->thread.join();
    }
    audio_pipelines.clear();
#endif

    for (auto& p : pipelines) p->running=false;
    pipelines.clear(); // stops networking and releases held input before source teardown
    if(g_failed || startup_failed){status.state("error","Capture, audio, encoder or network stopped unexpectedly; see diagnostic log");return 1;}
    status.state("stopped", "Session ended; pairing credentials invalidated");
    return 0;
}
