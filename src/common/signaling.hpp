// Metuxa application signaling carried only inside authenticated, pinned TLS.
// The secure server consumes AUTH before this parser. Lines are newline-framed
// ASCII, bounded to 4096 bytes; JSON bodies are compact and single-line.
//
//   HELLO <json>       server -> client: v5 stream params, media keys/capabilities
//   READY {"port":N}   client -> server: receiving UDP port on the TLS peer's IP
//   START              server -> client: media may begin
//   INPUT <event>      client -> server: bounded input event; "r" releases all
//   INPUT_STATE bool   server -> client: current input gate, true or false
//   PING               client heartbeat, echoed by server with current state
//   BYE                either side: close the authenticated session
//
// Every UDP RTP/RTCP packet uses the authenticated secure_transport envelope.
// Legacy plaintext clients are deliberately incompatible.

#pragma once

#include <cstdint>
#include <string>

namespace metashare::signal {

// Reserved TCP port for the signaling server.
inline constexpr std::uint16_t kDefaultSignalingPort = 7878;

// One decoded signaling message. PING is a client->server keepalive so the
// server can distinguish an idle-but-alive client from a vanished one (and
// free the single per-monitor slot for reconnection).
enum class Type { kHello, kReady, kStart, kBye, kPing, kInput, kInputState };

struct Message {
    Type type;
    // JSON body for HELLO/READY, event body for INPUT; empty for START/BYE.
    std::string body;
};

// Serialize a Message to its wire line (without the trailing newline).
std::string serialize(const Message& m);

// Parse one wire line (newline already stripped). Returns false on malformed
// input; out is left untouched on failure.
bool parse(std::string_view line, Message& out);

}  // namespace metashare::signal
