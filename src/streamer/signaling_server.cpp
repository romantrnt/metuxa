#include "signaling_server.hpp"
#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>

namespace metashare::signal {
namespace {
using Clock = std::chrono::steady_clock;
bool wait_socket(int fd, short events, Clock::time_point deadline) {
    for (;;) {
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count();
        if (left <= 0) return false;
        pollfd p{fd,events,0};
        const int n = ::poll(&p,1,static_cast<int>(left));
        if (n < 0 && errno == EINTR) continue;
        return n > 0 && (p.revents & events);
    }
}
}
Server::~Server() { stop(); }
bool Server::start(std::uint16_t port, OnConnect on_connect, OnMessage on_message, std::string& err) {
    if (running_ || accept_thread_.joinable()) { err="TLS server already started"; return false; }
    if (!credentials_) { err="TLS credentials required; plaintext is not supported"; return false; }
    listen_fd_=::socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);
    if(listen_fd_<0) { err=std::strerror(errno); return false; }
    int yes=1; ::setsockopt(listen_fd_,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof(yes));
    sockaddr_in addr{}; addr.sin_family=AF_INET; addr.sin_addr.s_addr=htonl(INADDR_ANY); addr.sin_port=htons(port);
    if(::bind(listen_fd_,reinterpret_cast<sockaddr*>(&addr),sizeof(addr))<0 || ::listen(listen_fd_,4)<0) {
        err=std::strerror(errno); ::close(listen_fd_); listen_fd_=-1; return false;
    }
    socklen_t alen=sizeof(addr); ::getsockname(listen_fd_,reinterpret_cast<sockaddr*>(&addr),&alen); bound_port_=ntohs(addr.sin_port);
    running_=true;
    accept_thread_=std::thread([this, listener=listen_fd_, on_connect=std::move(on_connect),on_message=std::move(on_message)] {
        // OpenSSL socket BIO may write(). Block SIGPIPE on this thread only.
        sigset_t blocked; sigemptyset(&blocked); sigaddset(&blocked,SIGPIPE); pthread_sigmask(SIG_BLOCK,&blocked,nullptr);
        while(running_) {
            sockaddr_in peer{}; socklen_t size=sizeof(peer);
            int fd=::accept4(listener,reinterpret_cast<sockaddr*>(&peer),&size,SOCK_CLOEXEC|SOCK_NONBLOCK);
            if(fd<0) { if(!running_) break; if(errno==EINTR) continue; break; }
            client_loop(fd,peer,on_connect,on_message);
            if(running_) std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    });
    return true;
}
void Server::stop() {
    running_=false;
    if(listen_fd_>=0) ::shutdown(listen_fd_,SHUT_RDWR);
    {
        std::lock_guard lk(send_mu_);
        int fd=client_fd_.load(); if(fd>=0) ::shutdown(fd,SHUT_RDWR);
    }
    if(accept_thread_.joinable()) accept_thread_.join();
    if(listen_fd_>=0) { ::close(listen_fd_); listen_fd_=-1; }
}
bool Server::has_client() const { return authenticated_; }
bool Server::write_line_locked(const std::string& line) {
    if(!tls_ || line.size()>4096) return false;
    auto deadline=Clock::now()+std::chrono::seconds(3); std::size_t off=0;
    while(off<line.size() && running_) {
        int n=SSL_write(tls_,line.data()+off,static_cast<int>(line.size()-off));
        if(n>0) { off+=n; continue; }
        int e=SSL_get_error(tls_,n);
        if(e!=SSL_ERROR_WANT_READ && e!=SSL_ERROR_WANT_WRITE) return false;
        if(!wait_socket(client_fd_,e==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,deadline)) return false;
    }
    return off==line.size();
}
bool Server::send(const Message& m) {
    std::lock_guard lk(send_mu_);
    if(!authenticated_) return false;
    return write_line_locked(serialize(m)+'\n');
}
bool Server::read_line(int fd,std::string& line,std::string& pending,int timeout_ms) {
    auto deadline=Clock::now()+std::chrono::milliseconds(timeout_ms);
    while(running_) {
        auto end=pending.find('\n');
        if(end!=std::string::npos) { line=pending.substr(0,end); pending.erase(0,end+1); return line.size()<=4096 && line.find('\0')==std::string::npos; }
        if(pending.size()>4096) return false;
        char buf[1024]; int n,e;
        {
            std::lock_guard lk(send_mu_);
            if(!tls_) return false;
            n=SSL_read(tls_,buf,sizeof(buf)); e=n>0?SSL_ERROR_NONE:SSL_get_error(tls_,n);
        }
        if(n>0) { pending.append(buf,n); continue; }
        if(e!=SSL_ERROR_WANT_READ && e!=SSL_ERROR_WANT_WRITE) return false;
        if(!wait_socket(fd,e==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,deadline)) return false;
    }
    return false;
}
bool Server::pairing_peer_alive(int fd) {
    pollfd p{fd,POLLIN,0};
    if(::poll(&p,1,0)<0) return errno==EINTR;
    if(p.revents&(POLLHUP|POLLERR|POLLNVAL)) return false;
    std::lock_guard lock(send_mu_);
    if(!tls_) return false;
    if(!(p.revents&POLLIN) && SSL_pending(tls_)==0) return true;
    // Consume TLS post-handshake records if present. Application bytes after
    // REVEAL are forbidden, including AUTH/READY/INPUT and extra reveals.
    char byte; const int n=SSL_read(tls_,&byte,1);
    if(n>0) return false;
    const int error=SSL_get_error(tls_,n);
    return error==SSL_ERROR_WANT_READ || error==SSL_ERROR_WANT_WRITE;
}
void Server::pairing_loop(int fd,const sockaddr_in& peer,std::string_view commitment,std::string& pending) {
    auto gate=credentials_->pairing_gate();
    auto send_pair=[&](const std::string& value) { std::lock_guard lock(send_mu_); return write_line_locked(value+'\n'); };
    std::array<std::uint8_t,32> committed{};
    if(!gate || SSL_version(tls_)!=TLS1_3_VERSION || !pending.empty() || !security::unhex(commitment,committed.data(),committed.size())) {
        send_pair("ERROR PAIRING"); return;
    }
    const auto ticket=gate->reserve();
    if(!ticket) { send_pair("ERROR PAIRING_CLOSED"); return; }
    auto finish=[&](std::string_view reason) { gate->finish(ticket->id,reason); };
    if(!send_pair("PAIR_CHALLENGE1 "+ticket->id+" "+ticket->nonce)) { finish("disconnected"); return; }
    std::string reveal;
    const auto left=std::chrono::duration_cast<std::chrono::milliseconds>(ticket->deadline-Clock::now()).count();
    if(left<=0 || !read_line(fd,reveal,pending,static_cast<int>(std::min<std::int64_t>(5000,left)))) {
        finish("disconnected"); return;
    }
    std::array<std::uint8_t,32> actual{};
    const bool valid=reveal.size()==77 && reveal.compare(0,13,"PAIR_REVEAL1 ")==0 && pending.empty() &&
        security::unhex(security::pairing_commitment(credentials_->fingerprint(),std::string_view(reveal).substr(13)),actual.data(),actual.size()) &&
        CRYPTO_memcmp(actual.data(),committed.data(),actual.size())==0;
    if(!valid) { send_pair("ERROR PAIRING"); finish("invalid"); return; }
    const auto code=security::pairing_comparison(credentials_->fingerprint(),std::string_view(reveal).substr(13),ticket->nonce,ticket->id);
    OPENSSL_cleanse(reveal.data(),reveal.size()); reveal.clear();
    char address[INET_ADDRSTRLEN]{};
    if(!::inet_ntop(AF_INET,&peer.sin_addr,address,sizeof address) || code.empty() ||
       !send_pair("PAIR_WAIT1") || !gate->present(ticket->id,code,address)) { finish("invalid"); return; }
    while(running_) {
        if(!pairing_peer_alive(fd)) { finish("disconnected"); return; }
        const auto decision=gate->decision(ticket->id);
        if(decision==security::PairingGate::Decision::Approved) {
            // No media callback is run on this connection. Reconnect using the
            // approved certificate pin and token through the regular AUTH path.
            gate->issue(ticket->id,[&] {
                return running_ && pairing_peer_alive(fd) && send_pair("PAIRED1 "+credentials_->pairing_code());
            });
            return;
        }
        if(decision!=security::PairingGate::Decision::Waiting) {
            send_pair("ERROR PAIRING"); finish(decision==security::PairingGate::Decision::Rejected?"rejected":"expired"); return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    finish("disconnected");
}
void Server::client_loop(int fd,const sockaddr_in& peer,const OnConnect& on_connect,const OnMessage& on_message) {
    {
        std::lock_guard lk(send_mu_); client_fd_=fd;
        tls_=SSL_new(credentials_->context());
        if(tls_) SSL_set_fd(tls_,fd);
    }
    bool connected=false;
    auto cleanup=[&] {
        {
            std::lock_guard lk(send_mu_); authenticated_=false;
            SSL_free(tls_); tls_=nullptr; client_fd_=-1; ::shutdown(fd,SHUT_RDWR); ::close(fd);
        }
        if(connected && on_disconnect) on_disconnect();
    };
    bool handshake=false; auto deadline=Clock::now()+std::chrono::seconds(5);
    while(running_ && tls_) {
        int n,e;
        { std::lock_guard lk(send_mu_); n=SSL_accept(tls_); e=n==1?SSL_ERROR_NONE:SSL_get_error(tls_,n); }
        if(n==1) { handshake=true; break; }
        if((e!=SSL_ERROR_WANT_READ && e!=SSL_ERROR_WANT_WRITE) || !wait_socket(fd,e==SSL_ERROR_WANT_READ?POLLIN:POLLOUT,deadline)) break;
    }
    std::string line,pending;
    if (!handshake) { cleanup(); return; }
    const bool auth_line = read_line(fd,line,pending,5000);
    if(auth_line && line.size()==70 && line.compare(0,6,"PAIR1 ")==0) {
        pairing_loop(fd,peer,std::string_view(line).substr(6),pending);
        OPENSSL_cleanse(line.data(),line.size()); cleanup(); return;
    }
    const bool accepted = auth_line && line.size()==37 && line.compare(0,5,"AUTH ")==0 && credentials_->authenticate(std::string_view(line).substr(5));
    OPENSSL_cleanse(line.data(),line.size()); line.clear();
    if (!accepted) {
        if (auth_line) { std::lock_guard lk(send_mu_); write_line_locked("ERROR AUTH\n"); }
        cleanup(); return;
    }
    authenticated_=true; connected=true;
    try {
        if(on_connect) on_connect(peer);
        auto window=Clock::now(); unsigned messages=0;
        while(running_ && read_line(fd,line,pending,10000)) {
            const auto now=Clock::now();
            if(now-window>=std::chrono::seconds(1)) { window=now; messages=0; }
            if(++messages>1000) break;
            Message msg{};
            if(!parse(line,msg) || (msg.type!=Type::kReady && msg.type!=Type::kInput && msg.type!=Type::kDisplayOrientation && msg.type!=Type::kPing && msg.type!=Type::kBye)) break;
            if(on_message) on_message(msg);
            if(msg.type==Type::kBye) break;
        }
    } catch (...) { /* All callback failures must still release the session. */ }
    cleanup();
}
} // namespace metashare::signal
