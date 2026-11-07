// Deterministic discovery transport stub; no socket is actually opened.
#include "discovery.hpp"
#include "protocol.hpp"
#include <cassert>
#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <iostream>
#include <mutex>
#include <netinet/in.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
namespace {
std::mutex mu;
std::condition_variable cv;
std::deque<int> packets;
std::atomic<unsigned> replies{0};
std::atomic<bool> receiving{false};
bool stopped=false;
constexpr int fakefd=900123;
}
extern "C" {
int __wrap_socket(int,int,int) { return fakefd; }
int __wrap_bind(int fd,const sockaddr*,socklen_t) { assert(fd==fakefd); return 0; }
int __wrap_setsockopt(int fd,int,int,const void*,socklen_t) { assert(fd==fakefd); return 0; }
ssize_t __wrap_recvfrom(int fd,void* data,size_t size,int flags,sockaddr* addr,socklen_t*) {
    assert(fd==fakefd && size==sizeof(metashare::proto::DiscoveryProbe) && (flags&MSG_TRUNC));
    receiving=true;
    std::unique_lock lock(mu); cv.wait(lock,[]{return stopped || !packets.empty();});
    if(stopped) { receiving=false; errno=EBADF; return -1; }
    const int length=packets.front(); packets.pop_front();
    metashare::proto::DiscoveryProbe probe{};
    std::memcpy(probe.magic,metashare::proto::kDiscoveryMagic,sizeof(probe.magic));
    probe.version=metashare::proto::kProtocolVersion;
    std::memcpy(data,&probe,sizeof(probe));
    auto* from=reinterpret_cast<sockaddr_in*>(addr); from->sin_family=AF_INET; from->sin_port=htons(54321);
    receiving=false; cv.notify_all(); return length;
}
ssize_t __wrap_sendto(int fd,const void*,size_t size,int flags,const sockaddr*,socklen_t) {
    assert(fd==fakefd && size==sizeof(metashare::proto::DiscoveryOffer) && (flags&MSG_DONTWAIT));
    ++replies; cv.notify_all(); return static_cast<ssize_t>(size);
}
int __wrap_shutdown(int fd,int) { assert(fd==fakefd); std::lock_guard lock(mu); stopped=true;cv.notify_all();return 0; }
int __real_close(int);
int __wrap_close(int fd) { if(fd!=fakefd)return __real_close(fd); assert(stopped && !receiving.load());return 0; }
}
int main() {
    {
        std::lock_guard lock(mu);
        packets.push_back(sizeof(metashare::proto::DiscoveryProbe)+1);
        packets.push_back(sizeof(metashare::proto::DiscoveryProbe)-1);
        for(int i=0;i<25;++i)packets.push_back(sizeof(metashare::proto::DiscoveryProbe));
    }
    metashare::DiscoveryResponder responder; std::string error;
    assert(responder.start(7878,3,error));
    assert(!responder.start(7878,3,error));
    {
        std::unique_lock lock(mu);
        assert(cv.wait_for(lock,std::chrono::milliseconds(500),[]{return packets.empty();}));
    }
    responder.stop(); responder.stop();
    assert(replies==20);
    std::cout<<"PASS: exact discovery size, oversized datagram rejection, global reply rate limit, repeated stop, join-before-close; all socket operations mocked.\n";
}
