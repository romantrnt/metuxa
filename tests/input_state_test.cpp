#include "app_support.hpp"
#include "input_event.hpp"
#include <cassert>
#include <iostream>
#include <vector>
using namespace metashare;
struct Sink:InputSink{
    int moves=0,scrolls=0;std::vector<std::pair<int,bool>> buttons,keys;
    void pointer_motion(double,double)override{++moves;}
    void pointer_button(int b,bool p)override{buttons.emplace_back(b,p);}
    void pointer_scroll(int,int)override{++scrolls;}
    void key(std::uint32_t k,bool p)override{keys.emplace_back(k,p);}
};
int main(){
    input::Event e{};
    for(const std::string s:{"m 0 1","m 0.25 0.75","b 272 1","b 279 0","s -120 120","k 65507 1","k 16778256 0"})assert(input::parse(s,e));
    for(const std::string s:{"","m 0","m nan 0","m inf 0","m 1.1 0","m 0 -1","m 0 0 junk","b 1 1","b 272 2","b 280 1","s 121 0","s 0 -121","k -1 1","k 18446744073709551616 1","k 4294967296 1","k 0 1","k 65536 1","k 16832512 1","k 1 -1","k 1 1 extra"})assert(!input::parse(s,e));
    int n=42;assert(app::integer("120",n)&&n==120);for(auto s:{"","1x"," 1","+1","999999999999999999"})assert(!app::integer(s,n));
    assert(app::quote("a\n\"b\\")=="\"a\\u000a\\\"b\\\\\"");
    Sink sink;
    {app::InputState state(&sink);
    auto send=[&](const char*s){assert(input::parse(s,e));state.accept(e);};
    send("b 272 1");send("b 272 1");send("k 65507 1");send("m 0.5 0.5");
    assert(sink.buttons.size()==1&&sink.keys.size()==1&&sink.moves==1);
    state.set_allowed(false);assert(sink.buttons.size()==2&&!sink.buttons.back().second);assert(sink.keys.size()==2&&!sink.keys.back().second);
    send("m 1 1");send("b 272 1");send("k 65 1");assert(sink.moves==1&&sink.buttons.size()==2&&sink.keys.size()==2);
    state.set_allowed(true);send("b 273 1");send("k 66 1");state.release();state.release();assert(sink.buttons.size()==4&&sink.keys.size()==4);
    send("b 274 1");}
    assert(sink.buttons.size()==6&&!sink.buttons.back().second);
    std::cout<<"PASS: strict bounded input parsing, settings numeric parsing, JSON escaping, input gate/release/dedup/destructor.\n";
}
