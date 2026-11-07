// Remote-input events — decoded from the signaling channel's INPUT lines.
//
// The wire body is a compact space-separated form (trivially parseable, like
// READY's port). One event per line:
//
//   m <x> <y>       pointer move; normalized floats 0..1 in the streamed
//                   surface (the client doesn't know the host resolution)
//   b <btn> <0|1>   pointer button; Linux evdev code (BTN_LEFT=272,
//                   BTN_RIGHT=273, BTN_MIDDLE=274), 1=pressed
//   s <v> <h>       discrete scroll steps; positive = down / right
//   k <sym> <0|1>   keyboard key; X11 keysym (Unicode maps to
//                   0x01000000+codepoint), 1=pressed
//
// Keysyms (not evdev keycodes) are used for the keyboard so the client can
// send committed IME text without either side needing an XKB layout: the
// compositor's virtual keyboard resolves keysyms directly.

#pragma once

#include <cstdint>
#include <cmath>
#include <locale>
#include <sstream>
#include <string>

namespace metashare::input {

struct Event {
    enum class Kind { kMove, kButton, kScroll, kKey };
    Kind kind{};
    double x = 0, y = 0;       // kMove, normalized 0..1
    int button = 0;            // kButton, evdev code
    bool pressed = false;      // kButton / kKey
    std::uint32_t keysym = 0;  // kKey
    int scroll_v = 0;          // kScroll, positive = down
    int scroll_h = 0;          // kScroll, positive = right
};

// Parse one INPUT body. Returns false on malformed input; out is left in an
// unspecified state on failure.
inline bool parse(const std::string& body, Event& out) {
    if (body.size() < 3 || body.size() > 128 || body[1] != ' ') return false;
    // Classic locale, exact token count, bounded integer conversion. Reject
    // trailing garbage, NaN/Inf, signed/overflowed keysyms and invalid states.
    std::istringstream in(body.substr(2));
    in.imbue(std::locale::classic());
    Event parsed{};
    switch (body[0]) {
    case 'm': {
        double x, y;
        if (!(in >> x >> y) || !std::isfinite(x) || !std::isfinite(y) ||
            x < 0 || x > 1 || y < 0 || y > 1) return false;
        parsed.kind=Event::Kind::kMove; parsed.x=x; parsed.y=y; break;
    }
    case 'b': {
        int b, state;
        if (!(in >> b >> state) || b < 272 || b > 279 || (state!=0 && state!=1)) return false;
        parsed.kind=Event::Kind::kButton; parsed.button=b; parsed.pressed=state==1; break;
    }
    case 's': {
        int v,h;
        if (!(in >> v >> h) || v < -120 || v > 120 || h < -120 || h > 120) return false;
        parsed.kind=Event::Kind::kScroll; parsed.scroll_v=v; parsed.scroll_h=h; break;
    }
    case 'k': {
        long long k; int state;
        if (!(in >> k >> state) || k<=0 || k>0x0110ffff || (state!=0 && state!=1)) return false;
        // X11 legacy keysyms, or valid Unicode codepoints in X11's encoding.
        if(k>0xffff && (k<0x01000000 || (k>=0x0100d800 && k<=0x0100dfff))) return false;
        parsed.kind=Event::Kind::kKey; parsed.keysym=static_cast<std::uint32_t>(k); parsed.pressed=state==1; break;
    }
    default: return false;
    }
    in >> std::ws;
    if (!in.eof()) return false;
    out=parsed; return true;
}

}  // namespace metashare::input
