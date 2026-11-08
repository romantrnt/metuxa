#include "secure_transport.hpp"
#include "signaling_server.hpp"
#include "rtp_server.hpp"
#include <arpa/inet.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstring>
#include <iostream>
#include <future>
#include <thread>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>
#include <openssl/x509.h>
using namespace metashare;
using namespace std::chrono_literals;
static void require(bool ok,const char* name) { if(!ok) { std::cerr<<"FAILED: "<<name<<'\n'; std::abort(); } }
template<class F> static bool eventually(F f) { for(int i=0;i<400;++i) { if(f()) return true; std::this_thread::sleep_for(5ms); } return f(); }
static int connect_tcp(unsigned port) {
    int fd=socket(AF_INET,SOCK_STREAM,0); require(fd>=0,"socket");
    timeval timeout{2,0}; setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)); setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_port=htons(port); address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    require(connect(fd,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"loopback connect"); return fd;
}
struct Client {
    int fd; SSL_CTX* context; SSL* ssl;
    explicit Client(unsigned port):fd(connect_tcp(port)),context(SSL_CTX_new(TLS_client_method())),ssl(SSL_new(context)) {
        SSL_set_fd(ssl,fd); require(SSL_connect(ssl)==1,"TLS connect");
    }
    ~Client() { SSL_free(ssl); SSL_CTX_free(context); shutdown(fd,SHUT_RDWR); close(fd); }
    void send(const std::string& s) { require(SSL_write(ssl,s.data(),s.size())==static_cast<int>(s.size()),"TLS write"); }
    std::string line() { std::string s; char c; while(SSL_read(ssl,&c,1)==1) { if(c=='\n') return s; s+=c; if(s.size()>5000) break; } return s; }
};
static void crypto_tests() {
    security::MediaCipher sender,receiver;
    const std::string key(64,'1'),prefix="01020304";
    require(sender.configure(key,prefix,1)&&receiver.configure(key,prefix,1),"configure");
    const std::uint8_t plain[]={0x80,0x60,0,1,0,0,0,1,0,0,0,7,0x65,0xaa};
    std::vector<std::uint8_t> packet,out;
    require(sender.seal(plain,sizeof(plain),packet),"seal");
    require(receiver.open(packet.data(),packet.size(),out) && out==std::vector<std::uint8_t>(plain,plain+sizeof(plain)),"open");
    require(!receiver.open(packet.data(),packet.size(),out),"replay rejected");
    security::MediaCipher other;
    require(other.configure(std::string(64,'2'),prefix,1),"wrong key configure");
    require(!other.open(packet.data(),packet.size(),out),"wrong key rejected");
    require(other.configure(key,prefix,2),"wrong direction configure");
    require(!other.open(packet.data(),packet.size(),out),"reflection rejected");
    require(!receiver.open(plain,sizeof(plain),out),"plaintext rejected");
    require(sender.seal(plain,sizeof(plain),packet),"second seal");
    auto corrupt=packet; corrupt.back()^=1;
    require(!receiver.open(corrupt.data(),corrupt.size(),out),"corruption rejected");
    require(receiver.open(packet.data(),packet.size(),out),"forgery does not advance replay state");
    std::vector<std::vector<std::uint8_t>> packets;
    for(int i=0;i<2050;++i) { require(sender.seal(plain,sizeof(plain),packet),"window seal"); packets.push_back(packet); }
    require(receiver.open(packets[2049].data(),packets[2049].size(),out),"forward jump");
    require(!receiver.open(packets[0].data(),packets[0].size(),out),"old window rejected");
    require(receiver.open(packets[2048].data(),packets[2048].size(),out),"reordering allowed");
    require(!receiver.open(packets[2048].data(),packets[2048].size(),out),"reordered duplicate rejected");
    require(!sender.seal(plain,0,out),"empty rejected");
    receiver.clear(); require(!receiver.open(packet.data(),packet.size(),out),"closed key rejected");
    // Stable cross-language golden vector, generated with an independent Java provider.
    security::MediaCipher golden; golden.configure(key,prefix,1); golden.seal(plain,sizeof(plain),packet);
    std::cout<<"VECTOR "<<security::hex(packet.data(),packet.size())<<'\n';
}
static std::string field(const std::string& body,const std::string& key) {
    const auto begin=body.find("\""+key+"\":\""); require(begin!=std::string::npos,"HELLO string field");
    const auto value=begin+key.size()+4, end=body.find('"',value); require(end!=std::string::npos,"HELLO quoted value");
    return body.substr(value,end-value);
}
static unsigned number(const std::string& body,const std::string& key) {
    const auto begin=body.find("\""+key+"\":"); require(begin!=std::string::npos,"HELLO number field");
    return std::stoul(body.substr(begin+key.size()+3));
}
static void rtp_tests(const std::shared_ptr<security::ServerCredentials>& credentials) {
    RtpServer server; std::string error;
    server.set_security(credentials); server.set_id(0); server.set_monitor_count(3);
    server.set_video_format({640,360,30,1,proto::Codec::kH264}); server.set_input_enabled(true);
    std::atomic<int> keys=0,releases=0,inputs=0;
    server.on_keyframe_request=[&]{++keys;}; server.on_input_release=[&]{++releases;};
    server.on_input=[&](const input::Event&){++inputs;};
    require(server.start(0,error),"RTP server start");
    int media=socket(AF_INET,SOCK_DGRAM,0); require(media>=0,"media socket");
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    require(bind(media,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"media bind");
    socklen_t address_size=sizeof(address); getsockname(media,reinterpret_cast<sockaddr*>(&address),&address_size);
    timeval timeout{1,0}; setsockopt(media,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); auto hello=client.line();
        require(hello.find("\"audio\"")==std::string::npos,"no phantom audio");
        require(hello.find("\"input\":true")!=std::string::npos && number(hello,"monitor_count")==3,"accurate capabilities");
        security::MediaCipher decrypt,encrypt;
        require(decrypt.configure(field(hello,"s2c_key"),field(hello,"s2c_prefix"),1),"media receive keys");
        require(encrypt.configure(field(hello,"c2s_key"),field(hello,"c2s_prefix"),2),"feedback keys");
        sockaddr_in server_address=address; server_address.sin_port=htons(number(hello,"udp_port"));
        client.send("READY {\"port\":"+std::to_string(ntohs(address.sin_port))+"}\n");
        require(client.line()=="START" && eventually([&]{return server.peer_count()==1 && keys==1;}),"RTP ready");
        require(client.line()=="INPUT_STATE true","initial input state");
        const std::uint8_t frame[]={0,0,0,1,0x65,0xaa,0xbb,0xcc}; server.broadcast_video(frame,sizeof(frame),0,true);
        std::uint8_t wire[2048]; auto size=recv(media,wire,sizeof(wire),0); std::vector<std::uint8_t> plain;
        require(size>32 && std::memcmp(wire,"MXG1",4)==0 && decrypt.open(wire,size,plain) && plain.size()>=12,"encrypted actual RTP");
        std::uint8_t pli[12]={0x81,206,0,2}; const unsigned ssrc=number(hello,"ssrc");
        for(int i=0;i<4;++i) pli[8+i]=static_cast<std::uint8_t>(ssrc>>(24-i*8));
        auto send_feedback=[&](int fd,const std::uint8_t* data,std::size_t n) { require(sendto(fd,data,n,0,reinterpret_cast<sockaddr*>(&server_address),sizeof(server_address))==static_cast<ssize_t>(n),"feedback send"); };
        send_feedback(media,pli,sizeof(pli)); std::this_thread::sleep_for(30ms); require(keys==1,"plaintext RTCP ignored");
        std::vector<std::uint8_t> sealed; encrypt.seal(pli,sizeof(pli),sealed);
        int stranger=socket(AF_INET,SOCK_DGRAM,0); send_feedback(stranger,sealed.data(),sealed.size()); close(stranger);
        auto corrupted=sealed; corrupted.back()^=1; send_feedback(media,corrupted.data(),corrupted.size());
        std::this_thread::sleep_for(30ms); require(keys==1,"wrong endpoint and forged RTCP ignored");
        send_feedback(media,sealed.data(),sealed.size()); require(eventually([&]{return keys==2;}),"authenticated RTCP accepted");
        std::this_thread::sleep_for(300ms); send_feedback(media,sealed.data(),sealed.size());
        std::this_thread::sleep_for(30ms); require(keys==2,"RTCP replay ignored");
        client.send("INPUT k 65 1\nINPUT r\n"); require(eventually([&]{return inputs==1 && releases==1;}),"input and explicit release");
    }
    require(eventually([&]{return releases==2 && server.peer_count()==0;}),"disconnect releases held input");
    server.stop(); close(media);
    // Enabled secondary monitor still must not advertise duplicate audio.
    server.set_id(1); server.set_audio_enabled(true); require(server.start(0,error),"restart server");
    { Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); require(client.line().find("\"audio\"")==std::string::npos,"audio primary only"); }
    server.stop();
}

static void metadata_probe_tests(const std::shared_ptr<security::ServerCredentials>& credentials) {
    RtpServer server; std::string error;
    server.set_security(credentials); server.set_video_format({360,640,30,1,proto::Codec::kH264});
    server.set_audio_enabled(true); server.set_input_enabled(true);
    std::atomic<int> started{0}, closed{0}, inputs{0}, keyframes{0};
    server.on_client_state = [&](bool active) { if (active) ++started; else ++closed; };
    server.on_input = [&](const input::Event&) { ++inputs; };
    server.on_keyframe_request = [&] { ++keyframes; };
    require(server.start(0,error), "metadata probe listener");
    int media=socket(AF_INET,SOCK_DGRAM,0); require(media>=0,"metadata probe media socket");
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    require(bind(media,reinterpret_cast<sockaddr*>(&address),sizeof(address))==0,"metadata probe media bind");
    socklen_t address_size=sizeof(address); getsockname(media,reinterpret_cast<sockaddr*>(&address),&address_size);
    timeval timeout{1,0}; setsockopt(media,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    const std::uint8_t frame[]={0,0,0,1,0x65,0xaa,0xbb,0xcc}, audio[]={0x78,0x45,0x23};
    std::string probe_hello;
    {
        Client probe(server.bound_port()); probe.send("AUTH "+credentials->pairing_code()+"\n");
        probe_hello=probe.line();
        require(probe_hello.starts_with("HELLO ") && number(probe_hello,"width")==360 && number(probe_hello,"height")==640,
                "authenticated metadata-only probe reads actual portrait dimensions");
        server.broadcast_video(frame,sizeof(frame),0,true);
        server.broadcast_audio(0,audio,sizeof(audio),0);
        pollfd wait{media,POLLIN,0};
        require(poll(&wait,1,80)==0 && server.peer_count()==0 && started==0 && inputs==0 && keyframes==0,
                "HELLO without READY starts no media, input or streaming callback");
    }
    require(eventually([&] { return closed==1 && server.peer_count()==0; }), "metadata probe releases monitor slot");
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
        const auto hello=client.line();
        for (const char* key : {"s2c_key", "c2s_key"})
            require(field(hello,key)!=field(probe_hello,key), "normal stream receives fresh keys after metadata probe");
        client.send("READY {\"port\":"+std::to_string(ntohs(address.sin_port))+"}\n");
        require(client.line()=="START" && client.line()=="INPUT_STATE true", "normal READY succeeds after metadata probe");
        require(eventually([&] { return started==1 && server.peer_count()==1 && keyframes==1; }),
                "only full stream reports connected");
        server.broadcast_video(frame,sizeof(frame),33333,true);
        std::uint8_t wire[2048]; const auto size=recv(media,wire,sizeof(wire),0);
        security::MediaCipher current, previous;
        require(current.configure(field(hello,"s2c_key"),field(hello,"s2c_prefix"),1) &&
                previous.configure(field(probe_hello,"s2c_key"),field(probe_hello,"s2c_prefix"),1), "probe media ciphers");
        std::vector<std::uint8_t> plain;
        require(size>32 && current.open(wire,size,plain) && !previous.open(wire,size,plain),
                "subsequent media uses new session, not stale probe keys");
        require(inputs==0, "metadata probe and reconnect inject no input");
    }
    require(eventually([&] { return closed==2 && server.peer_count()==0; }), "normal stream cleans up after metadata probe");
    server.stop(); close(media);
}

static void display_tests(const std::shared_ptr<security::ServerCredentials>& credentials) {
    for (auto source : {display::Source::Physical, display::Source::Window, display::Source::Unknown,
                        display::Source::Test, display::Source::Virtual}) {
        RtpServer server; std::string error; std::atomic<int> changes{0}, inputs{0};
        server.set_security(credentials); server.set_video_format({640,360,30,1,proto::Codec::kH264});
        server.set_display_source(source, true); // Invalid capability on physical must stay false.
        server.set_input_enabled(true); server.on_input = [&](const input::Event&) { ++inputs; };
        server.on_display_orientation = [&](bool portrait) {
            require(portrait, "portrait request passed correctly"); ++changes;
            server.update_display_format({360,640,30,1,proto::Codec::kH264}); return true;
        };
        require(server.start(0,error), "display test listener");
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
            auto hello = client.line();
            const bool virtual_source = source == display::Source::Virtual;
            require(hello.find(virtual_source ? "\"orientation\":true" : "\"orientation\":false") != std::string::npos,
                    "source-verified orientation capability");
            client.send("READY {\"port\":12345}\n");
            require(client.line()=="START" && client.line()=="INPUT_STATE true", "orientation starts authenticated stream");
            client.send("DISPLAY_ORIENTATION portrait\n");
            require(client.line() == (virtual_source ? "DISPLAY_RESULT ok 360 640" : "DISPLAY_RESULT unsupported"), "orientation result");
            if (virtual_source) {
                require(server.peer_count()==0 && changes==1, "media paused until reconnect");
                client.send("DISPLAY_ORIENTATION landscape\nINPUT k 65 1\nPING\n");
                require(client.line()=="DISPLAY_RESULT busy" && client.line()=="PING" && client.line()=="INPUT_STATE true", "repeated request rejected");
                require(inputs==0 && changes==1, "no stale input or queued second resize");
            } else require(changes==0 && server.peer_count()==1, "physical source cannot mutate");
        }
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
            auto hello=client.line();
            require(number(hello,"width")==(source==display::Source::Virtual ? 360u : 640u), "reconnect HELLO effective dimensions");
            client.send("DISPLAY_ORIENTATION portrait\n");
            require(client.line().empty(), "orientation before READY refused");
        }
        server.stop();
    }
    {
        RtpServer server; std::string error; std::atomic<int> attempts{0};
        server.set_security(credentials); server.set_video_format({640,360,30,1,proto::Codec::kH264});
        server.set_display_source(display::Source::Virtual,true);
        server.on_display_orientation = [&](bool) { ++attempts; return false; };
        require(server.start(0,error), "failed resize listener");
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); client.line();
            client.send("READY {\"port\":12345}\n"); client.line(); client.line();
            client.send("DISPLAY_ORIENTATION portrait\n");
            require(client.line()=="DISPLAY_RESULT failed" && attempts==1 && server.peer_count()==0,
                    "failure requires reconnect, not stale media");
        }
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
            require(number(client.line(),"width")==640, "restored dimensions after failed resize");
            client.send("READY {\"port\":12345}\n"); client.line(); client.line();
            client.send("DISPLAY_ORIENTATION portrait\n");
            require(client.line()=="DISPLAY_RESULT busy" && attempts==1, "resize rate limited across reconnects");
        }
        server.stop();
    }
    {
        RtpServer server; std::string error; std::atomic<int> attempts{0};
        server.set_security(credentials); server.set_video_format({640,360,30,1,proto::Codec::kH264});
        server.set_display_source(display::Source::Virtual,true);
        server.on_display_orientation = [&](bool) {
            ++attempts; server.fail_display(); return false; // Both resize and restoration failed.
        };
        require(server.start(0,error), "unrecoverable resize listener");
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); client.line();
            client.send("READY {\"port\":12345}\n"); client.line(); client.line();
            client.send("DISPLAY_ORIENTATION portrait\n");
            require(client.line()=="DISPLAY_RESULT restart_required", "explicit recovery result after failed restoration");
            require(client.line().empty() && attempts==1 && server.peer_count()==0,
                    "failed monitor closes after recovery message without media");
        }
        {
            Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
            require(client.line().empty() && attempts==1, "unrecoverable monitor cannot resume stale dimensions");
        }
        server.stop();
    }
    // A disconnect during an accepted request cannot enqueue work for a later peer.
    RtpServer server; std::string error; std::promise<void> entered, finish;
    auto finished = finish.get_future();
    server.set_security(credentials); server.set_video_format({640,360,30,1,proto::Codec::kH264});
    server.set_display_source(display::Source::Virtual,true);
    server.on_display_orientation = [&](bool) {
        entered.set_value(); finished.wait();
        server.update_display_format({360,640,30,1,proto::Codec::kH264}); return true;
    };
    require(server.start(0,error), "disconnect resize listener");
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); client.line();
        client.send("READY {\"port\":12345}\n"); client.line(); client.line();
        client.send("DISPLAY_ORIENTATION portrait\n");
        require(entered.get_future().wait_for(2s)==std::future_status::ready, "accepted resize entered");
    }
    finish.set_value();
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n");
        auto hello=client.line(); require(number(hello,"width")==360 && number(hello,"height")==640, "disconnect reconnect waits for settled format");
    }
    server.stop();
}

int main(int argc,char** argv) {
    std::string error;
    auto credentials=security::ServerCredentials::create(error);
    require(bool(credentials),"ephemeral credentials");
    require(credentials->fingerprint().size()==64 && credentials->pairing_code().size()==32,"credential dimensions");
    require(!credentials->authenticate(std::string(32,'0')),"invalid token");
    std::atomic<int> connected=0,disconnected=0,messages=0;
    signal::Server server;
    require(!server.start(0,{},{},error),"no plaintext start");
    server.set_security(credentials);
    server.on_disconnect=[&]{ ++disconnected; };
    require(server.start(0,[&](const sockaddr_in&){ ++connected; server.send({signal::Type::kHello,"{\"test\":true}"}); },[&](const signal::Message& message){ ++messages; if(message.type==signal::Type::kReady) server.send({signal::Type::kStart,{}}); },error),"server start");
    if(argc>1 && std::string(argv[1])=="--interop-server") {
        RtpServer actual;
        actual.set_security(credentials); actual.set_video_format({640,360,30,1,proto::Codec::kH264});
        actual.on_input=[](const input::Event&){};
        std::atomic<bool> had_peer=false;
        actual.on_client_state=[&](bool connected){if(connected)had_peer=true;};
        require(actual.start(0,error),"interop RTP server");
        std::cout<<actual.bound_port()<<' '<<credentials->fingerprint()<<' '<<credentials->pairing_code()<<std::endl;
        const auto start=std::chrono::steady_clock::now();
        for(int i=0;i<3000;++i) {
            const auto elapsed=std::chrono::steady_clock::now()-start;
            actual.set_input_enabled(elapsed>5s && elapsed<10s);
            if(had_peer && actual.peer_count()==0)break;
            std::this_thread::sleep_for(10ms);
        }
        actual.stop(); server.stop(); require(had_peer,"Java interop completed"); return 0;
    }
    signal::Message state{};
    require(signal::parse("INPUT_STATE true",state) && state.type==signal::Type::kInputState && state.body=="true", "input state parse");
    require(signal::parse("INPUT_STATE false",state) && state.body=="false", "disabled input parse");
    require(!signal::parse("INPUT_STATE yes",state), "invalid input state parse");
    crypto_tests();
    rtp_tests(credentials);
    display_tests(credentials);
    metadata_probe_tests(credentials);
    {
        int fd=connect_tcp(server.bound_port());
        const char* plain="READY {\"port\":9}\n"; send(fd,plain,strlen(plain),MSG_NOSIGNAL); shutdown(fd,SHUT_WR);
        char response[100]; int n=recv(fd,response,sizeof(response),0); require(n<=0,"plaintext rejected without HELLO"); close(fd);
    }
    require(connected==0,"plaintext never authenticated");
    {
        Client client(server.bound_port());
        X509* cert=SSL_get1_peer_certificate(client.ssl); unsigned char digest[32]; unsigned len=0;
        require(cert && X509_digest(cert,EVP_sha256(),digest,&len)==1 && security::hex(digest,len)==credentials->fingerprint(),"server certificate pin"); X509_free(cert);
        client.send("AUTH "+std::string(32,'0')+"\n"); require(client.line()=="ERROR AUTH","wrong token rejected");
    }
    require(connected==0,"wrong token no callback");
    {
        Client client(server.bound_port()); std::string auth="AUTH "+credentials->pairing_code()+"\n";
        for(char c:auth) client.send(std::string(1,c));
        require(client.line()=="HELLO {\"test\":true}","fragmented authenticated HELLO");
        client.send("READY {\"port\":12345}\nINPUT r\nPING\n"); require(client.line()=="START","coalesced messages");
        require(eventually([&]{return messages==3;}),"all buffered messages processed");
        client.send("INPUT k 65 ");
    }
    require(eventually([&]{return disconnected==1 && !server.has_client();}),"abrupt partial input releases session exactly once");
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); require(!client.line().empty(),"reconnect");
        client.send(std::string(4097,'x')+"\n"); require(client.line().empty(),"oversize line rejected");
    }
    require(eventually([&]{return disconnected==2;}),"oversize cleanup");
    {
        Client client(server.bound_port()); client.send("AUTH "+credentials->pairing_code()+"\n"); require(!client.line().empty(),"stop connect");
        auto begin=std::chrono::steady_clock::now(); server.stop();
        require(std::chrono::steady_clock::now()-begin<500ms,"stop wakes blocked read");
    }
    require(disconnected==3,"stop disconnect callback");
    server.stop();
    std::cout<<"C++ security tests passed\n";
}
