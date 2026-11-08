// Pure lifecycle regression tests: no listening socket, portal request, device
// capture or encoder launch. The production CLI entry point is not invoked.
#define main metuxa_cli_entry_not_run
#include "../src/streamer/main.cpp"
#undef main
#include <cassert>
#include <condition_variable>
#include <future>
#include <iostream>
#include <limits>

namespace {
struct VideoStub final : FrameSource, InputSink {
    std::vector<std::string>& order;
    explicit VideoStub(std::vector<std::string>& o) : order(o) {}
    bool start(std::string&) override { assert(false); return false; }
    void stop() override { order.push_back("source stop"); }
    SourceFormat format() const override { return {320,240,60,1}; }
    int next_frame(AVFrame**, std::int64_t&) override { assert(false); return -1; }
    void pointer_motion(double,double) override {}
    void pointer_scroll(int,int) override {}
    void pointer_button(int, bool down) override { order.push_back(down?"button down":"button up"); }
    void key(std::uint32_t,bool down) override { order.push_back(down?"key down":"key up"); }
};
#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
struct BlockingAudio final : AudioSource {
    std::mutex mu;
    std::condition_variable cv;
    bool stopped=false;
    std::promise<void> entered;
    bool start(std::string&) override { assert(false); return false; }
    void stop() override { std::lock_guard lock(mu); stopped=true; cv.notify_all(); }
    AudioFormat format() const override { return {48000,2}; }
    int next_chunk(const std::int16_t**,std::int64_t&) override {
        std::unique_lock lock(mu); entered.set_value(); cv.wait(lock,[&]{return stopped;}); return -1;
    }
};
#endif
struct ResizableStub final : FrameSource {
    SourceFormat current{640,360,30,1};
    display::Source kind = display::Source::Virtual;
    int calls = 0;
    bool reject = false, reject_restore = false;
    bool start(std::string&) override { assert(false); return false; }
    void stop() override {}
    SourceFormat format() const override { return current; }
    int next_frame(AVFrame**, std::int64_t&) override { assert(false); return -1; }
    display::Source display_source() const override { return kind; }
    bool can_set_orientation() const override { return true; } // Backend must check type too.
    bool resize_desktop(int w, int h, std::string&) override {
        ++calls;
        current.width = w; current.height = h; // Partial change even on failure.
        return !(reject && (calls == 1 || reject_restore));
    }
};
void test_orientation_pipeline() {
    for (auto kind : {display::Source::Physical, display::Source::Window, display::Source::Unknown,
                      display::Source::Test, display::Source::Virtual}) {
        for (int failure = 0; failure < 3; ++failure) {
            Pipeline pipeline;
            auto source = std::make_unique<ResizableStub>(); auto raw = source.get();
            source->kind = kind; source->reject = failure > 0; source->reject_restore = failure > 1;
            pipeline.source = std::move(source);
            pipeline.server = std::make_unique<RtpServer>();
            pipeline.encoder = std::make_unique<Encoder>();
            pipeline.encoder_config.width = 640; pipeline.encoder_config.height = 360;
            pipeline.encoder_config.prefer_hardware = false;
            pipeline.encoder_config.preferred_codec = proto::Codec::kH264;
            std::string err;
            assert(pipeline.encoder->open(pipeline.encoder_config, err));
            pipeline.running = true;
            if (kind != display::Source::Virtual) {
                assert(!pipeline.set_orientation(true) && raw->calls == 0);
                continue;
            }
            assert(pipeline.set_orientation(true) == (failure == 0));
            if (failure == 0) {
                assert(raw->calls == 1 && raw->current.width == 360 && raw->current.height == 640);
                assert(pipeline.encoder_config.width == 360 && pipeline.encoder_config.height == 640);
                assert(pipeline.set_orientation(true) && raw->calls == 1); // Idempotent retry.
                assert(pipeline.set_orientation(false) && raw->calls == 2);
            } else {
                assert(raw->calls == 2 && raw->current.width == 640 && raw->current.height == 360);
                assert(pipeline.running == (failure == 1));
                assert(pipeline.encoder_config.width == 640 && pipeline.encoder_config.height == 360);
            }
            pipeline.running = false;
            const int count = raw->calls;
            assert(!pipeline.set_orientation(true) && raw->calls == count);
        }
    }
}
void test_input_teardown() {
    std::vector<std::string> order;
    {
        auto pipeline=std::make_unique<Pipeline>();
        auto source=std::make_unique<VideoStub>(order);
        pipeline->input_state=std::make_shared<app::InputState>(source.get());
        input::Event event;
        assert(input::parse("b 272 1",event)); pipeline->input_state->accept(event);
        assert(input::parse("k 65507 1",event)); pipeline->input_state->accept(event);
        pipeline->source=std::move(source);
        pipeline->running=true;
        auto raw=pipeline.get();
        pipeline->thread=std::thread([raw]{ while(raw->running) std::this_thread::yield(); });
    }
    assert((order==std::vector<std::string>{"button down","key down","button up","key up","source stop"}));
}
void test_signal_flags() {
    g_stop=false; g_input_gate=true;
    std::thread writer([]{for(int i=0;i<10000;++i){on_signal(SIGUSR1);on_signal(SIGUSR2);}on_signal(SIGTERM);});
    while(!g_stop.load()) (void)g_input_gate.load();
    writer.join(); assert(g_stop.load() && g_input_gate.load());
    g_stop=false;
}
void test_status_backpressure() {
    int pipefd[2]; assert(::pipe(pipefd)==0);
    app::Status status; std::string err; assert(status.init(pipefd[1],err));
    const auto begin=std::chrono::steady_clock::now();
    unsigned count=0; while(status.emit(std::string(1000,'x'))) {assert(++count<100000);}
    assert(std::chrono::steady_clock::now()-begin<std::chrono::seconds(1));
    assert(!status.emit(std::string(4096,'x'))); // newline would exceed atomic-write bound
    char buf[4096]; while(::read(pipefd[0],buf,sizeof(buf))>0) { if(::fcntl(pipefd[0],F_SETFL,O_NONBLOCK)==-1) assert(false); }
    assert(status.emit("{\"state\":\"stopped\"}"));
    ::close(pipefd[0]); ::close(pipefd[1]);
}
void test_cli_validation() {
    auto check=[](std::initializer_list<const char*> args,bool expected) {
        std::vector<char*> argv; for(auto arg:args)argv.push_back(const_cast<char*>(arg));
        Options opt; assert(parse_args(static_cast<int>(argv.size()),argv.data(),opt)==expected);
    };
    check({"metuxa","--source","test","--audio","none","--fps","72"},true);
    check({"metuxa","--source","test","--audio","mic"},false);
    check({"metuxa","--source","test","--fps","0"},false);
    check({"metuxa","--source","test","--port","65536"},false);
    check({"metuxa","--source","test","--width","321"},false);
    check({"metuxa","--source","window","--input"},false);
}
}
int main() {
    test_orientation_pipeline(); test_input_teardown(); test_signal_flags(); test_status_backpressure(); test_cli_validation();
#if defined(METASHARE_HAVE_PORTAL) || defined(METASHARE_HAVE_MUTTER)
    {
        auto audio=std::make_unique<AudioChannel>();
        auto source=std::make_unique<BlockingAudio>(); auto raw=source.get();
        auto entered=raw->entered.get_future(); audio->source=std::move(source);
        audio->running=true;
        audio->thread=std::thread([raw]{ const std::int16_t* pcm=nullptr; std::int64_t pts=0; assert(raw->next_chunk(&pcm,pts)==-1); });
        assert(entered.wait_for(std::chrono::seconds(1))==std::future_status::ready);
        audio.reset(); // destructor must wake + join instead of std::terminate
    }
    {
        PipeWireAudioSource audio(PipeWireAudioSource::Mode::kSystemSinkMonitor);
        audio.on_state_changed(3,-1); // synthetic terminal state; no start()
        const std::int16_t* pcm=nullptr; std::int64_t pts=0;
        assert(audio.next_chunk(&pcm,pts)==-1);
        audio.stop(); audio.stop();
    }
#endif
#ifdef METASHARE_HAVE_PORTAL
    for (const char* name : {"org.freedesktop.DBus.Error.UnknownMethod", "org.freedesktop.DBus.Error.UnknownInterface",
            "org.freedesktop.DBus.Error.NotSupported", "org.freedesktop.portal.Error.NotImplemented", "org.freedesktop.portal.Error.NotSupported"})
        assert(portal_remote_desktop_unavailable(name));
    for (const char* name : {"", "portal request rejected (code 1)", "portal request rejected (code 2)",
            "portal request cancelled", "portal request timed out", "org.freedesktop.DBus.Error.AccessDenied",
            "org.freedesktop.portal.Error.NotAllowed", "org.freedesktop.DBus.Error.NoReply",
            "org.freedesktop.portal.Error.Failed", "org.freedesktop.DBus.Error.ServiceUnknown",
            "AccessDenied: org.freedesktop.DBus.Error.UnknownMethod"})
        assert(!portal_remote_desktop_unavailable(name));
    for (std::uint32_t devices : {0u,1u,2u,4u,5u,6u,8u}) assert(!portal_grants_complete_input(devices));
    for (std::uint32_t devices : {3u,7u,11u,0xffffffffu}) assert(portal_grants_complete_input(devices));
    {
        PortalOptions opts; opts.cancel_requested=[] {return true;};
        PortalPipeWireSource source(opts); std::string error;
        assert(!source.start(error) && error=="portal request cancelled");
        source.on_stream_ended(); AVFrame* frame=nullptr; std::int64_t pts=0;
        assert(source.latest_frame(&frame,pts)==-1);
        source.stop(); source.stop();
    }
#endif
    std::cout<<"PASS: pipeline release/join order, audio unblock/join, signal atomics, bounded status pipe, strict CLI, pre-portal cancellation, actual portal device grants, no fallback for denial/cancellation and terminal source callbacks.\n";
}
