#include "signaling.hpp"

#include <sstream>
#include <string>
#include <string_view>

namespace metashare::signal {

std::string serialize(const Message& m) {
    std::ostringstream os;
    switch (m.type) {
    case Type::kHello: os << "HELLO " << m.body; break;
    case Type::kReady: os << "READY " << m.body; break;
    case Type::kInput: os << "INPUT " << m.body; break;
    case Type::kDisplayOrientation: os << "DISPLAY_ORIENTATION " << m.body; break;
    case Type::kDisplayResult: os << "DISPLAY_RESULT " << m.body; break;
    case Type::kInputState: os << "INPUT_STATE " << m.body; break;
    case Type::kStart: return "START";
    case Type::kBye: return "BYE";
    case Type::kPing: return "PING";
    }
    return os.str();
}

bool parse(std::string_view line, Message& out) {
    if(line.size()>4096 || line.find('\n')!=std::string_view::npos || line.find('\0')!=std::string_view::npos)return false;
    // Strip a trailing '\r' if the peer used CRLF.
    if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
    if (line == "START") {
        out.type = Type::kStart;
        out.body.clear();
        return true;
    }
    if (line == "BYE") {
        out.type = Type::kBye;
        out.body.clear();
        return true;
    }
    if (line == "PING") {
        out.type = Type::kPing;
        out.body.clear();
        return true;
    }

    auto sp = line.find(' ');
    if (sp == std::string_view::npos) return false;
    auto cmd = line.substr(0, sp);
    // Compact JSON bodies carry no newlines, so the rest of the line is the
    // whole body. Keep the exact bytes (no base64 round-trip).
    auto rest = line.substr(sp + 1);
    if(rest.empty())return false;

    if (cmd == "HELLO") {
        out.type = Type::kHello;
        out.body = std::string(rest);
        return true;
    }
    if (cmd == "READY") {
        out.type = Type::kReady;
        out.body = std::string(rest);
        return true;
    }
    if (cmd == "DISPLAY_ORIENTATION") {
        if (rest != "portrait" && rest != "landscape") return false;
        out = {Type::kDisplayOrientation, std::string(rest)};
        return true;
    }
    if (cmd == "DISPLAY_RESULT") {
        out = {Type::kDisplayResult, std::string(rest)};
        return true;
    }
    if (cmd == "INPUT_STATE") {
        if (rest != "true" && rest != "false") return false;
        out.type = Type::kInputState;
        out.body = std::string(rest);
        return true;
    }
    if (cmd == "INPUT") {
        out.type = Type::kInput;
        out.body = std::string(rest);
        return true;
    }
    return false;
}

}  // namespace metashare::signal
