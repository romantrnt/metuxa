#include "pipewire_audio_source.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <utility>
#include <thread>

#include <pipewire/pipewire.h>
#include <spa/param/audio/format-utils.h>
#include <spa/param/audio/raw.h>
#include <spa/param/props.h>
#include <spa/pod/builder.h>

namespace metashare {

namespace {

void cb_state_changed(void* data, enum pw_stream_state old,
                      enum pw_stream_state state, const char* error) {
    std::fprintf(stderr, "[pw-audio] state: %s%s%s\n",
                 pw_stream_state_as_string(state), error ? " - " : "",
                 error ? error : "");
    static_cast<PipeWireAudioSource*>(data)->on_state_changed(old, state);
}

void cb_process(void* data) {
    static_cast<PipeWireAudioSource*>(data)->on_process();
}

const pw_stream_events kStreamEvents = {
    .version = PW_VERSION_STREAM_EVENTS,
    .state_changed = cb_state_changed,
    .process = cb_process,
};

const char* role_for(PipeWireAudioSource::Mode m) {
    return m == PipeWireAudioSource::Mode::kSystemSinkMonitor ? "Music"
                                                              : "Communication";
}

}  // namespace

PipeWireAudioSource::PipeWireAudioSource(Mode mode, AudioFormat fmt)
    : mode_(mode), fmt_(fmt) {}

PipeWireAudioSource::~PipeWireAudioSource() { stop(); }

bool PipeWireAudioSource::start(std::string& err) {
    if (fmt_.sample_rate < 1000 / kAudioFrameMs || fmt_.channels <= 0 || fmt_.channels > 2 || fmt_.sample_rate > 192000) {
        err = "invalid audio format";
        return false;
    }
    ready_ = false;
    failed_ = false;
    buffer_.assign(
        static_cast<std::size_t>(fmt_.sample_rate / 20) * fmt_.channels, 0);
    read_pos_ = buffered_samples_ = 0;
    pw_init(nullptr, nullptr);

    loop_ = pw_thread_loop_new("metashare-audio", nullptr);
    if (!loop_) {
        err = "pw_thread_loop_new failed";
        return false;
    }
    context_ = pw_context_new(pw_thread_loop_get_loop(loop_), nullptr, 0);
    if (!context_) {
        err = "pw_context_new failed";
        return false;
    }

    pw_thread_loop_lock(loop_);
    if (pw_thread_loop_start(loop_) < 0) {
        pw_thread_loop_unlock(loop_);
        err = "pw_thread_loop_start failed";
        return false;
    }
    // Connect to the user's default PipeWire daemon (NOT via a portal fd —
    // audio capture bypasses the screencast portal entirely).
    core_ = pw_context_connect(context_, nullptr, 0);
    if (!core_) {
        pw_thread_loop_unlock(loop_);
        err = "pw_context_connect failed (is PIPEWIRE_REMOTE / the session "
              "PipeWire daemon running?)";
        return false;
    }

    pw_properties* props = pw_properties_new(
        PW_KEY_MEDIA_TYPE, "Audio", PW_KEY_MEDIA_CATEGORY, "Capture",
        PW_KEY_MEDIA_ROLE, role_for(mode_), nullptr);
    if (mode_ == Mode::kSystemSinkMonitor) {
        // The magic key that tells PipeWire to attach us to the monitor of the
        // default sink instead of the default source — i.e. capture what the
        // speakers are playing ("loopback"/"desktop audio").
        pw_properties_set(props, PW_KEY_STREAM_CAPTURE_SINK, "true");
    }

    stream_ = pw_stream_new(core_, "metashare-audio", props);
    if (!stream_) { pw_thread_loop_unlock(loop_); err="pw_stream_new failed"; return false; }
    pw_stream_add_listener(stream_, &stream_listener_, &kStreamEvents, this);

    // Request the exact PCM layout consumed by AudioEncoder. A negotiable
    // 1..8 channel range would make stereo sample interpretation unsafe.
    std::uint8_t pod_buf[1024];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(pod_buf, sizeof(pod_buf));
    spa_audio_info_raw info{};
    info.format = SPA_AUDIO_FORMAT_S16;
    info.rate = static_cast<std::uint32_t>(fmt_.sample_rate);
    info.channels = static_cast<std::uint32_t>(fmt_.channels);
    info.position[0] = fmt_.channels == 1 ? SPA_AUDIO_CHANNEL_MONO : SPA_AUDIO_CHANNEL_FL;
    if (fmt_.channels > 1) info.position[1] = SPA_AUDIO_CHANNEL_FR;
    const spa_pod* params[] = {spa_format_audio_raw_build(&b, SPA_PARAM_EnumFormat, &info)};
    running_ = true;

    // PW_KEY_TARGET_OBJECT set to empty -> PipeWire picks the default sink's
    // monitor (or default source) via the session manager.
    const int flags = PW_STREAM_FLAG_AUTOCONNECT | PW_STREAM_FLAG_MAP_BUFFERS;
    if (pw_stream_connect(stream_, PW_DIRECTION_INPUT,
                          /*target_id=*/PW_ID_ANY,
                          static_cast<pw_stream_flags>(flags), params, 1) < 0) {
        pw_thread_loop_unlock(loop_);
        err = "pw_stream_connect failed";
        return false;
    }
    pw_thread_loop_unlock(loop_);

    for (int i = 0; i < 200 && !ready_ && !failed_; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (failed_ || !ready_) {
        running_ = false;
        err = failed_ ? "PipeWire audio stream failed during startup" : "PipeWire audio negotiation timed out";
        return false;
    }
    std::fprintf(stderr, "[pw-audio] %s capture started (%dch @ %d Hz)\n",
                 mode_ == Mode::kSystemSinkMonitor ? "system-output" : "mic",
                 fmt_.channels, fmt_.sample_rate);
    return true;
}

void PipeWireAudioSource::on_state_changed(int old_state, int state) {
    if (state == PW_STREAM_STATE_PAUSED || state == PW_STREAM_STATE_STREAMING)
        ready_ = true;
    if (state == PW_STREAM_STATE_ERROR ||
        (state == PW_STREAM_STATE_UNCONNECTED && old_state != PW_STREAM_STATE_UNCONNECTED)) {
        failed_ = true;
        running_ = false;
        cv_.notify_all();
    }
}

void PipeWireAudioSource::stop() {
    running_ = false;
    cv_.notify_all();

    if (loop_) pw_thread_loop_lock(loop_);
    if (stream_) {
        pw_stream_destroy(stream_);
        stream_ = nullptr;
    }
    if (core_) {
        pw_core_disconnect(core_);
        core_ = nullptr;
    }
    if (loop_) pw_thread_loop_unlock(loop_);
    if (loop_) pw_thread_loop_stop(loop_);
    if (context_) {
        pw_context_destroy(context_);
        context_ = nullptr;
    }
    if (loop_) {
        pw_thread_loop_destroy(loop_);
        loop_ = nullptr;
    }

    std::lock_guard<std::mutex> lk(mu_);
    buffer_.clear();
    read_pos_ = buffered_samples_ = 0;
}

void PipeWireAudioSource::on_process() {
    pw_buffer* b = pw_stream_dequeue_buffer(stream_);
    if (!b) return;
    spa_buffer* sbuf = b->buffer;
    if (sbuf->n_datas < 1) {
        pw_stream_queue_buffer(stream_, b);
        return;
    }

    // Interleaved S16 has a single datas[0] plane.
    const spa_data& d = sbuf->datas[0];
    if (!d.data || !d.chunk || d.chunk->size == 0 ||
        (d.chunk->flags & SPA_CHUNK_FLAG_CORRUPTED) || d.chunk->offset > d.maxsize ||
        d.chunk->size > d.maxsize - d.chunk->offset || d.chunk->offset % sizeof(std::int16_t)) {
        pw_stream_queue_buffer(stream_, b);
        return;
    }

    const auto* src = reinterpret_cast<const std::int16_t*>(
        static_cast<const std::uint8_t*>(d.data) + d.chunk->offset);
    std::size_t n_samples =
        d.chunk->size / sizeof(std::int16_t) / fmt_.channels * fmt_.channels;

    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const std::int64_t pts =
        std::chrono::duration_cast<std::chrono::microseconds>(now).count();

    {
        std::lock_guard<std::mutex> lk(mu_);
        const std::size_t capacity = buffer_.size();
        if (!capacity) { pw_stream_queue_buffer(stream_, b); return; }
        if (n_samples > capacity) {
            src += n_samples - capacity;
            n_samples = capacity;
        }
        if (buffered_samples_ + n_samples > capacity) {
            const auto discard = buffered_samples_ + n_samples - capacity;
            read_pos_ = (read_pos_ + discard) % capacity;
            buffered_samples_ -= discard;
        }
        const auto write_pos = (read_pos_ + buffered_samples_) % capacity;
        const auto first = std::min(n_samples, capacity - write_pos);
        std::memcpy(buffer_.data() + write_pos, src,
                    first * sizeof(std::int16_t));
        std::memcpy(buffer_.data(), src + first,
                    (n_samples - first) * sizeof(std::int16_t));
        buffered_samples_ += n_samples;
        pts_usec_ =
            pts - static_cast<std::int64_t>(buffered_samples_ / fmt_.channels) *
                      1'000'000 / fmt_.sample_rate;
    }
    cv_.notify_one();

    pw_stream_queue_buffer(stream_, b);
}

int PipeWireAudioSource::next_chunk(const std::int16_t** out,
                                    std::int64_t& pts_usec) {
    const std::size_t want =
        static_cast<std::size_t>(fmt_.sample_rate * kAudioFrameMs / 1000) *
        fmt_.channels;

    std::unique_lock<std::mutex> lk(mu_);
    cv_.wait_for(lk, std::chrono::milliseconds(200),
                 [&] { return buffered_samples_ >= want || !running_; });
    if (!running_ && buffered_samples_ == 0) return -1;
    if (buffered_samples_ < want) return 0;

    // Move the head out of the shared buffer into a stable loan. loan_ is a
    // member so subsequent on_process() appends can't invalidate the caller's
    // pointer.
    loan_.resize(want);
    const auto first = std::min(want, buffer_.size() - read_pos_);
    std::memcpy(loan_.data(), buffer_.data() + read_pos_,
                first * sizeof(std::int16_t));
    std::memcpy(loan_.data() + first, buffer_.data(),
                (want - first) * sizeof(std::int16_t));
    read_pos_ = (read_pos_ + want) % buffer_.size();
    buffered_samples_ -= want;
    pts_usec = pts_usec_;
    pts_usec_ += static_cast<std::int64_t>(want / fmt_.channels) * 1'000'000 /
                 fmt_.sample_rate;
    *out = loan_.data();
    return static_cast<int>(want);
}

}  // namespace metashare
