#include "encoder.hpp"
#include "audio_encoder.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
#include <vector>
using namespace metashare;
int main(){
    Encoder encoder;std::string err;EncoderConfig cfg;cfg.width=320;cfg.height=240;cfg.fps_num=30;cfg.prefer_hardware=false;cfg.bitrate_kbps=1000;
    assert(encoder.open(cfg,err));int packets=0;
    auto sink=[&](const std::uint8_t* d,std::size_t n,std::int64_t,bool){assert(d&&n);++packets;};
    int i=0;for(auto dimensions:{std::pair{640,480},std::pair{320,240},std::pair{800,600},std::pair{640,480}}){
      AVFrame* f=av_frame_alloc();assert(f);f->format=AV_PIX_FMT_BGRA;f->width=dimensions.first;f->height=dimensions.second;assert(av_frame_get_buffer(f,32)==0);
      for(int y=0;y<f->height;y++)std::memset(f->data[0]+y*f->linesize[0],80,f->width*4);
      for(int n=0;n<4;n++){assert(encoder.encode(f,++i*33333,sink,err));}
      av_frame_free(&f);
    }
    encoder.flush(sink);assert(packets>0);assert(!encoder.encode(nullptr,0,sink,err));encoder.close();
    AudioEncoder audio;AudioEncoderConfig ac;ac.format={48000,2};assert(audio.open(ac,err));int ap=0;
    std::vector<std::int16_t> pcm(960,0);auto as=[&](const std::uint8_t*d,std::size_t n,std::int64_t){assert(d&&n);++ap;};
    for(int n=0;n<20;n++) { assert(audio.encode(pcm.data(),static_cast<int>(pcm.size()),n*10000,as,err)); }
    audio.flush(as);assert(ap>=20);
    std::cout<<"PASS: software video encode across repeated source resize, null-frame rejection, Opus encoding.\n";
}
