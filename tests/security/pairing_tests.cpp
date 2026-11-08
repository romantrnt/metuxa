#include "secure_transport.hpp"
#include "signaling_server.hpp"
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <iostream>
#include <mutex>
#include <openssl/err.h>
#include <sstream>
#include <thread>
#include <vector>
#include <sys/socket.h>
#include <unistd.h>
using namespace metashare;
using namespace std::chrono_literals;
static void check(bool value,const char* what) { if(!value) { std::cerr<<"FAIL pairing: "<<what<<'\n'; std::abort(); } }
template<class F> static bool eventually(F f) { for(int i=0;i<400;++i) { if(f())return true; std::this_thread::sleep_for(5ms); } return f(); }
struct Events {
    std::mutex mutex; std::condition_variable changed; std::vector<std::string> values;
    bool emit(const std::string& value) { std::lock_guard lock(mutex); values.push_back(value); changed.notify_all(); return true; }
    bool has(const std::string& value) { std::lock_guard lock(mutex); for(auto& e:values)if(e.find(value)!=std::string::npos)return true; return false; }
    bool wait(const std::string& value) {
        std::unique_lock lock(mutex);
        return changed.wait_for(lock,2s,[&] { for(const auto& e:values)if(e.find(value)!=std::string::npos)return true; return false; });
    }
    std::string last(const std::string& type) { std::lock_guard lock(mutex); for(auto it=values.rbegin();it!=values.rend();++it)if(it->find(type)!=std::string::npos)return *it; return {}; }
};
static std::string field(const std::string& value,const std::string& key) {
    auto pos=value.find("\""+key+"\":\""); check(pos!=std::string::npos,"event field"); pos+=key.size()+4;
    return value.substr(pos,value.find('"',pos)-pos);
}
struct Client {
    int fd=-1; SSL_CTX* ctx=nullptr; SSL* tls=nullptr;
    explicit Client(unsigned port) {
        fd=::socket(AF_INET,SOCK_STREAM,0); check(fd>=0,"socket");
        timeval timeout{2,0}; setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout);
        sockaddr_in addr{}; addr.sin_family=AF_INET; addr.sin_addr.s_addr=htonl(INADDR_LOOPBACK); addr.sin_port=htons(port);
        check(::connect(fd,reinterpret_cast<sockaddr*>(&addr),sizeof addr)==0,"connect");
        ctx=SSL_CTX_new(TLS_client_method()); check(ctx && SSL_CTX_set_min_proto_version(ctx,TLS1_3_VERSION)==1,"TLS13 only");
        tls=SSL_new(ctx); SSL_set_fd(tls,fd); check(SSL_connect(tls)==1,"TLS handshake");
    }
    ~Client() { SSL_free(tls); SSL_CTX_free(ctx); ::shutdown(fd,SHUT_RDWR); ::close(fd); }
    void send(const std::string& value) { check(SSL_write(tls,value.data(),value.size())==static_cast<int>(value.size()),"write"); }
    std::string line() { std::string result; char c; while(SSL_read(tls,&c,1)==1) { if(c=='\n')return result; result+=c; check(result.size()<4096,"line bound"); } return result; }
    bool closed() {
        ERR_clear_error(); char c; const int n=SSL_read(tls,&c,1);
        if(n>0)return false; // Any application byte, including a token, fails.
        const int error=SSL_get_error(tls,n);
        if(error==SSL_ERROR_ZERO_RETURN || (error==SSL_ERROR_SYSCALL && n==0))return true;
#ifdef SSL_R_UNEXPECTED_EOF_WHILE_READING
        // The server closes without close_notify. OpenSSL 3 reports this as an
        // SSL error; older versions return SSL_ERROR_SYSCALL with zero bytes.
        if(error==SSL_ERROR_SSL && ERR_GET_REASON(ERR_peek_last_error())==SSL_R_UNEXPECTED_EOF_WHILE_READING)return true;
#endif
        return false; // A read timeout is not evidence that the peer closed.
    }
    std::pair<std::string,std::string> challenge(const std::string& pin,const std::string& nonce) {
        send("PAIR1 "+security::pairing_commitment(pin,nonce)+"\n"); std::istringstream in(line());
        std::string kind,id,server; in>>kind>>id>>server; check(kind=="PAIR_CHALLENGE1" && id.size()==32 && server.size()==64,"challenge");
        return {id,server};
    }
};
static void vectors() {
    const std::string f="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    const std::string c="202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";
    const std::string s="404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f", id="606162636465666768696a6b6c6d6e6f";
    check(security::pairing_commitment(f,c)=="557f6cd7437080d8d2bc60df920c9538962852a1d21f73561442a23a6f10914a","independent SHA256 commit vector");
    check(security::pairing_comparison(f,c,s,id)=="41592 13575","independent SAS vector");
    check(security::pairing_commitment("bad",c).empty(),"invalid pin");
    check(security::pairing_comparison(f,c,s,"bad").empty(),"invalid request id");
    check(security::pairing_comparison(std::string(64,'0'),c,s,id)!="41592 13575","MITM leaf changes SAS");
    check(security::pairing_comparison(f,c,s,std::string(32,'0'))!="41592 13575","id bound");
    check(security::pairing_comparison(f,s,c,id)!="41592 13575","role/order bound");
}
static void gate_tests() {
    Events events; security::PairingGate gate([&](const auto& s){return events.emit(s);});
    check(!gate.reserve(),"unsolicited disabled"); check(gate.arm(),"explicit arm");
    auto ticket=gate.reserve(); check(bool(ticket),"reserve"); check(!gate.reserve(),"single pending");
    check(!gate.decide(ticket->id,true),"approval before reveal refused");
    check(!gate.present(ticket->id,"     123456","127.0.0.1"),"bad code refused");
    check(gate.present(ticket->id,"12345 67890","127.0.0.1"),"present");
    check(!gate.present(ticket->id,"12345 67890","127.0.0.1"),"repeat presentation refused");
    check(!gate.decide(std::string(32,'0'),true),"foreign id refused");
    check(gate.decide(ticket->id,true),"approve"); check(!gate.decide(ticket->id,true),"decision one shot");
    gate.close(); int writes=0;
    check(!gate.issue(ticket->id,[&]{++writes;return true;}) && writes==0,"close beats approved token issue");
    check(!gate.decide(ticket->id,true),"stale approval refused"); check(!gate.arm(),"cooldown");
    security::PairingGate failed([](const auto&){return false;}); check(!failed.arm() && !failed.reserve(),"lost status consumer fails closed");
    // One-way issuance, including a close racing a write. The lock establishes
    // whether cancellation won before issuance started, with no duplicate issue.
    security::PairingGate one([](const auto&){return true;}); check(one.arm(),"arm fresh");
    auto active=one.reserve(); check(bool(active),"reserve fresh");
    check(one.present(active->id,"12345 67890","127.0.0.1") && one.decide(active->id,true),"approve fresh");
    check(one.issue(active->id,[&]{++writes;return true;}),"issue approved");
    check(!one.issue(active->id,[&]{++writes;return true;}) && writes==1,"no replay of issue");
}
static void network_test(int mode) {
    std::string error; auto credentials=security::ServerCredentials::create(error); check(bool(credentials),"credentials");
    Events events; auto gate=std::make_shared<security::PairingGate>([&](const auto& s){return events.emit(s);});
    credentials->set_pairing_gate(gate); signal::Server server; server.set_security(credentials);
    std::atomic<unsigned> connects=0,messages=0;
    check(server.start(0,[&](const auto&){++connects;server.send({signal::Type::kHello,"{}"});},[&](const auto&){++messages;},error),"server start");
    if(mode!=0)check(gate->arm(),"network arm");
    {
        Client client(server.bound_port()); const std::string nonce(64,'1');
        if(mode==0) { client.send("PAIR1 "+security::pairing_commitment(credentials->fingerprint(),nonce)+"\n"); check(client.line()=="ERROR PAIRING_CLOSED","network closed gate"); }
        else {
            auto [id,challenge]=client.challenge(mode==11?std::string(64,'0'):credentials->fingerprint(),nonce);
            if(mode==1) { client.send("PAIR_REVEAL1 "+std::string(64,'2')+"\n"); check(client.line()=="ERROR PAIRING","commit mismatch"); }
            else if(mode==2) { client.send("PAIR_REVEAL1 "+nonce+"\nAUTH "+credentials->pairing_code()+"\n"); check(client.line()=="ERROR PAIRING","pipelined auth rejected"); }
            else if(mode==3) { client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait before secrets"); }
            else if(mode==4) { client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait reject"); check(eventually([&]{return gate->decide(id,false);}),"desktop reject"); check(client.line()=="ERROR PAIRING","reject no token"); }
            else if(mode==5) {
                client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait approve");
                check(eventually([&]{return events.has("pairing_request");}),"request shown");
                check(field(events.last("pairing_request"),"code")==security::pairing_comparison(credentials->fingerprint(),nonce,challenge,id),"network matching code");
                check(!server.has_client() && connects==0 && messages==0,"no media/input before approval");
                check(gate->decide(id,true),"desktop exact approval");
                check(client.line()=="PAIRED1 "+credentials->pairing_code(),"token only after approval");
                check(client.line().empty(),"pair socket closed without HELLO");
            } else if(mode==6) { client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait extra command"); client.send("INPUT k 65 1\n"); check(client.line().empty(),"input rejected before auth"); }
            else if(mode==7) {
                client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait close");
                // PAIR_WAIT1 is sent before present(). Wait for the status
                // notification so this case closes an already presented request.
                // close() acquires the gate lock after that notification returns.
                check(events.wait("pairing_request"),"request presented before close");
                gate->close(); check(!gate->decide(id,true),"close refuses stale approval");
                check(client.line()=="ERROR PAIRING","close after presentation no token");
                check(client.closed(),"close after presentation ends connection without secrets");
            }
            else if(mode==8) { check(!gate->decide(id,true),"early network approval refused"); client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","still needs decision"); }
            else if(mode==9) {
                timeval timeout{7,0}; setsockopt(client.fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout);
                const auto start=std::chrono::steady_clock::now();
                check(client.line().empty(),"missing reveal closes without secrets");
                check(std::chrono::steady_clock::now()-start<6s,"reveal deadline bounded");
            } else if(mode==10) {
                client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait stop");
                const auto start=std::chrono::steady_clock::now(); server.stop();
                check(std::chrono::steady_clock::now()-start<500ms,"stop wakes pairing");
            } else if(mode==11) {
                client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="ERROR PAIRING","MITM certificate commitment rejected");
                check(!events.has("pairing_request"),"MITM no approval prompt");
            } else if(mode==12) {
                // The server cannot present before receiving the reveal. Close
                // first, then release that protocol barrier to cover the other
                // ordering deterministically: present() fails and the peer closes.
                gate->close(); check(!gate->decide(id,true),"early close refuses stale approval");
                client.send("PAIR_REVEAL1 "+nonce+"\n"); check(client.line()=="PAIR_WAIT1","wait after early close");
                check(client.closed(),"close before presentation ends connection without secrets");
                check(!events.has("pairing_request"),"closed request never presented");
            }
        }
    }
    if(mode==3 || mode==8 || mode==9) check(eventually([&]{return events.has("disconnected");}),"disconnect removes pending");
    check(connects==0 && messages==0 && !server.has_client(),"pairing never enters stream callbacks");
    if(mode==5) { Client stream(server.bound_port()); stream.send("AUTH "+credentials->pairing_code()+"\n"); check(stream.line()=="HELLO {}","pinned AUTH path retained"); }
    server.stop();
}
static void network_expiry() {
    std::string error; auto credentials=security::ServerCredentials::create(error); check(bool(credentials),"expiry credentials");
    auto gate=std::make_shared<security::PairingGate>([](const auto&){return true;}); credentials->set_pairing_gate(gate);
    signal::Server server; server.set_security(credentials); std::atomic<int> connections=0;
    check(server.start(0,[&](const auto&){++connections;},{},error) && gate->arm(),"expiry server start");
    Client client(server.bound_port()); const std::string nonce(64,'2');
    client.challenge(credentials->fingerprint(),nonce); client.send("PAIR_REVEAL1 "+nonce+"\n");
    check(client.line()=="PAIR_WAIT1","expiry network wait");
    timeval timeout{65,0}; setsockopt(client.fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof timeout);
    check(client.line()=="ERROR PAIRING" && connections==0,"pending live TLS expires without credentials"); server.stop();
}
static void expiry_and_limits() {
    // Real monotonic-clock coverage; start both deadlines together.
    Events e; security::PairingGate idle([&](const auto& s){return e.emit(s);}),pending([&](const auto& s){return e.emit(s);});
    check(idle.arm() && pending.arm(),"expiry arm"); auto ticket=pending.reserve(); check(bool(ticket),"expiry reserve");
    check(pending.present(ticket->id,"12345 67890","127.0.0.1") && pending.decide(ticket->id,true),"expiry approved pending");
    const auto finish=security::PairingGate::Clock::now()+60s;
    security::PairingGate limited([](const auto&){return true;});
    std::string previous;
    for(int i=0;i<10;++i) {
        if(i)std::this_thread::sleep_for(2001ms);
        check(limited.arm(),"within attempt budget"); auto t=limited.reserve(); check(bool(t),"limited reserve");
        check(previous!=t->id,"fresh request id"); previous=t->id;
        limited.finish(t->id,"rejected");
    }
    std::this_thread::sleep_for(2001ms); check(!limited.arm() && !limited.reserve(),"ten attempt cap");
    std::this_thread::sleep_until(finish+50ms); idle.tick(); pending.tick();
    check(!idle.reserve(),"window expired"); int writes=0;
    check(!pending.issue(ticket->id,[&]{++writes;return true;}) && writes==0,"expiry beats approved issue");
    check(!pending.decide(ticket->id,true),"expired request cannot approve");
}
static int interop() {
    std::string error; auto credentials=security::ServerCredentials::create(error); check(bool(credentials),"interop credentials");
    std::mutex output;
    auto gate=std::make_shared<security::PairingGate>([&](const std::string& event){std::lock_guard lock(output);std::cout<<event<<std::endl;return true;});
    credentials->set_pairing_gate(gate); signal::Server server; server.set_security(credentials);
    check(server.start(0,[&](const auto&){server.send({signal::Type::kHello,"{}"});},{},error),"interop listen");
    { std::lock_guard lock(output);std::cout<<"PORT "<<server.bound_port()<<std::endl; }
    std::string command;
    while(std::getline(std::cin,command)) {
        if(command=="OPEN")gate->arm();
        else if(command=="CLOSE")gate->close();
        else if(command.starts_with("ACCEPT "))gate->decide(command.substr(7),true);
        else if(command.starts_with("REJECT "))gate->decide(command.substr(7),false);
        else if(command=="QUIT")break;
    }
    gate->close(); server.stop(); return 0;
}
int main(int argc,char** argv) {
    std::signal(SIGPIPE,SIG_IGN);
    if(argc>1 && std::string(argv[1])=="--interop")return interop();
    vectors(); gate_tests();
    std::thread slow(expiry_and_limits),network_deadline(network_expiry);
    for(int i=0;i<13;++i)network_test(i);
    slow.join(); network_deadline.join();
    std::cout<<"Pairing C++ tests passed (commit mismatch, injection, cancel, stale IDs, one-shot approval, expiry, limits).\n";
}
