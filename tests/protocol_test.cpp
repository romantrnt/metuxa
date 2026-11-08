#include "rtp_packetizer.hpp"
#include "signaling.hpp"
#include "protocol.hpp"
#include "display_control.hpp"
#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>
using namespace metashare;
using namespace metashare::rtp;
static std::uint32_t be32(const Packet& p, int i) { return (std::uint32_t(p[i])<<24)|(std::uint32_t(p[i+1])<<16)|(std::uint32_t(p[i+2])<<8)|p[i+3]; }
int main() {
  static_assert(sizeof(proto::DiscoveryProbe)==16);
  static_assert(sizeof(proto::DiscoveryOffer)==80);
  static_assert(proto::kProtocolVersion==5);
  Packet h(12); write_header(h.data(),96,true,65535,0x10203040,0x50607080);
  assert(h[0]==0x80 && h[1]==0xe0 && seq_of(h)==65535 && be32(h,4)==0x10203040 && be32(h,8)==0x50607080);
  for (bool hevc : {false,true}) {
    std::vector<std::uint8_t> au{0,0,0,1,static_cast<std::uint8_t>(hevc?0x26:0x65)};
    au.reserve(4200);
    if (hevc) au.push_back(1);
    au.insert(au.end(),4100,0x55);
    std::vector<Packet> out;
    H265RtpPacketizer h265(42); H264RtpPacketizer h264(42);
    PacketizerBase& packetizer=hevc?static_cast<PacketizerBase&>(h265):static_cast<PacketizerBase&>(h264);
    packetizer.packetize(au.data(),au.size(),1000000,out);
    assert(out.size()==3);
    std::vector<std::uint8_t> reconstructed;
    for (std::size_t i=0;i<out.size();++i) {
      const auto& p=out[i]; assert(p.size()<=kMaxPacketSize);
      assert(seq_of(p)==i && be32(p,4)==90000 && be32(p,8)==42);
      assert(bool(p[1]&0x80)==(i+1==out.size()));
      const int fu=hevc?14:13;
      assert(bool(p[fu]&0x80)==(i==0)); assert(bool(p[fu]&0x40)==(i+1==out.size()));
      reconstructed.insert(reconstructed.end(),p.begin()+fu+1,p.end());
    }
    assert(reconstructed==std::vector<std::uint8_t>(4100,0x55));
    out.clear(); const std::uint8_t small[]={0,0,1,0x26,1,0x55};
    packetizer.packetize(small,sizeof(small),2000000,out);
    assert(out.size()==1 && seq_of(out[0])==3 && out[0].size()==15 && (out[0][1]&0x80));
    out.clear(); packetizer.packetize(nullptr,0,0,out); assert(out.empty());
    const std::uint8_t malformed[]={0,0,1,0};
    packetizer.packetize(malformed,sizeof(malformed),0,out); assert(out.empty());
  }
  OpusRtpPacketizer opus(99); std::vector<Packet> audio;
  const std::uint8_t op[]={0x78,0x45,0x23}; opus.packetize(op,3,1000000,audio);
  assert(audio.size()==1 && audio[0].size()==15 && audio[0][1]==(0x80|111) && be32(audio[0],4)==48000);
  RetransmitBuffer buf(2); Packet result;
  buf.record(65535,{1}); buf.record(0,{2}); buf.record(1,{3});
  assert(!buf.get(65535,result) && buf.get(0,result) && result==Packet{2});
  assert(buf.get(1,result) && result==Packet{3}); buf.clear(); assert(!buf.get(1,result));
  for(auto type:{signal::Type::kHello,signal::Type::kReady,signal::Type::kInput,signal::Type::kStart,signal::Type::kBye,signal::Type::kPing}) {
    signal::Message input{type,"{\"port\":1234}"}, parsed{};
    assert(signal::parse(signal::serialize(input)+"\r",parsed) && parsed.type==type);
    if(type==signal::Type::kHello || type==signal::Type::kReady || type==signal::Type::kInput) assert(parsed.body==input.body);
  }
  for (auto orientation : {"portrait", "landscape"}) {
    signal::Message command{signal::Type::kDisplayOrientation, orientation}, parsed{};
    assert(signal::parse(signal::serialize(command), parsed) && parsed.body == orientation);
  }
  {
    signal::Message result{signal::Type::kDisplayResult, "restart_required"}, parsed{};
    assert(signal::serialize(result) == "DISPLAY_RESULT restart_required");
    assert(signal::parse(signal::serialize(result), parsed) && parsed.type == result.type && parsed.body == result.body);
  }
  for (auto invalid : {"DISPLAY_ORIENTATION 90", "DISPLAY_ORIENTATION portrait ", "DISPLAY_ORIENTATION Portrait", "DISPLAY_ORIENTATION", "DISPLAY_ORIENTATION landscape\n"}) {
    signal::Message unchanged{signal::Type::kPing, "unchanged"};
    assert(!signal::parse(invalid, unchanged) && unchanged.body == "unchanged");
  }
  assert(display::owns_virtual(4,4));
  for (unsigned requested : {0,1,2,3,5,7}) assert(!display::owns_virtual(requested,4));
  for (unsigned granted : {0,1,2,3,5,7}) assert(!display::owns_virtual(4,granted));
  int w = 0, height = 0;
  assert(display::dimensions(1920,1080,true,w,height) && w == 1080 && height == 1920);
  assert(display::dimensions(w,height,false,w,height) && w == 1920 && height == 1080);
  assert(display::dimensions(640,640,true,w,height) && w == 640 && height == 640);
  assert(!display::dimensions(8192,8192,true,w,height));
  assert(!display::dimensions(1921,1080,true,w,height));
  assert(!display::dimensions(-1,1080,true,w,height));
  signal::Message m{signal::Type::kPing,"sentinel"};
  assert(!signal::parse("NOT_A_COMMAND data",m) && m.type==signal::Type::kPing && m.body=="sentinel");
  assert(!signal::parse("",m));
  std::cout<<"PASS: protocol v5 structs, RTP headers, H264/H265 fragmentation and payload reconstruction, sequence/marker/timestamps, empty/malformed access units, Opus packetization, retransmit wrap eviction, signaling round-trips.\n";
}
