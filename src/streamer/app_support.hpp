// metuxa lifecycle helpers. Original MetaShare source remains MIT licensed.
#pragma once
#include <cerrno>
#include <charconv>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include "input_event.hpp"
#include "input_sink.hpp"
namespace metashare::app {
inline bool integer(std::string_view s, int& value) {
    if (s.empty()) return false;
    int parsed = 0;
    const auto [end, ec] = std::from_chars(s.data(), s.data()+s.size(), parsed);
    if (ec != std::errc{} || end != s.data()+s.size()) return false;
    value = parsed; return true;
}
inline std::string quote(std::string_view s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c >= 32 && c < 127) out += char(c);
        else if (c >= 128) out += char(c);
        else { char b[7]; std::snprintf(b,sizeof b,"\\u%04x",c); out += b; }
    }
    return out+'"';
}
class Status {
    int fd_ = -1; std::mutex mu_;
public:
    bool init(int fd, std::string& err) {
        if (fd < 0) { if (::isatty(STDOUT_FILENO)) fd=STDOUT_FILENO;
            else { err="Use metuxa-control or supply --status-fd N for the private pairing/status pipe."; return false; } }
        int flags=::fcntl(fd,F_GETFL);
        if(flags<0 || (flags&O_ACCMODE)==O_RDONLY) { err="status fd is not writable"; return false; }
        // A stopped GUI must never block capture/input cleanup.
        if(!::isatty(fd) && ::fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0) { err="cannot configure status pipe"; return false; }
        fd_=fd; return true;
    }
    bool emit(std::string json) {
        std::lock_guard<std::mutex> lock(mu_);
        json += '\n'; if(fd_<0 || json.size()>4096) return false;
        ssize_t n; do { n=::write(fd_,json.data(),json.size()); } while(n<0 && errno==EINTR);
        return n==static_cast<ssize_t>(json.size());
    }
    void state(std::string_view value, std::string_view message={}) {
        emit("{\"state\":"+quote(value)+",\"message\":"+quote(message)+"}");
    }
};
// Per-user advisory lock; never changes global app/OS security configuration.
class InstanceLock {
    int fd_=-1;
public:
    ~InstanceLock(){if(fd_>=0)::close(fd_);}
    bool acquire(std::string& err) {
        std::string path="/tmp/metuxa-server-"+std::to_string(::getuid())+".lock";
        fd_=::open(path.c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
        if(fd_<0){err="Cannot open private instance lock: "+std::string(std::strerror(errno));return false;}
        struct stat st{};
        if(::fstat(fd_,&st)<0 || st.st_uid!=::getuid() || !S_ISREG(st.st_mode) || st.st_nlink!=1) {err="Unsafe instance lock file";return false;}
        if(::flock(fd_,LOCK_EX|LOCK_NB)<0){err="Another metuxa server is already running for this user";return false;}
        return true;
    }
};
// Every remote pressed key/button is released on disconnect, gate-off and stop.
// Keep this state separate from the physical host input and never log its data.
class InputState {
    InputSink* sink_; std::mutex mu_; bool allowed_=true;
    std::set<int> buttons_; std::set<std::uint32_t> keys_;
    void release_locked(){
        if(sink_){for(int b:buttons_)sink_->pointer_button(b,false);for(auto k:keys_)sink_->key(k,false);}
        buttons_.clear();keys_.clear();
    }
public:
    explicit InputState(InputSink* sink):sink_(sink){}
    ~InputState(){release();}
    void release(){std::lock_guard<std::mutex> lock(mu_);release_locked();}
    void set_allowed(bool allowed){std::lock_guard<std::mutex> lock(mu_);if(!allowed)release_locked();allowed_=allowed;}
    void accept(const input::Event& e){
        std::lock_guard<std::mutex> lock(mu_);if(!allowed_ || !sink_)return;
        switch(e.kind){
        case input::Event::Kind::kMove:sink_->pointer_motion(e.x,e.y);break;
        case input::Event::Kind::kButton:
            if(e.pressed){if(!buttons_.insert(e.button).second)return;}
            else if(!buttons_.erase(e.button))return;
            sink_->pointer_button(e.button,e.pressed);break;
        case input::Event::Kind::kScroll:sink_->pointer_scroll(e.scroll_v,e.scroll_h);break;
        case input::Event::Kind::kKey:
            if(e.pressed){if(keys_.size()>=128 || !keys_.insert(e.keysym).second)return;}
            else if(!keys_.erase(e.keysym))return;
            sink_->key(e.keysym,e.pressed);break;
        }
    }
};
}
