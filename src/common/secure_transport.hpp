// Metuxa secure transport v1. TLS authenticates the server by an out-of-band
// certificate pin; a per-run random token authenticates the headset. Media uses
// independent directional AES-256-GCM keys delivered only over that TLS channel.
#pragma once
#include <array>
#include <bitset>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>
#include <openssl/ssl.h>

namespace metashare::security {
std::string hex(const std::uint8_t* data, std::size_t size);
bool unhex(std::string_view text, std::uint8_t* out, std::size_t size);

class ServerCredentials {
  public:
    // Explicit server Start only. Entirely in memory; nothing saved to disk.
    static std::shared_ptr<ServerCredentials> create(std::string& error);
    ~ServerCredentials();
    SSL_CTX* context() const { return ctx_; }
    const std::string& fingerprint() const { return fingerprint_; }
    const std::string& pairing_code() const { return code_; }
    bool authenticate(std::string_view code) const;
  private:
    ServerCredentials() = default;
    SSL_CTX* ctx_ = nullptr;
    std::string fingerprint_, code_;
};

// Wire: "MXG1" | direction:u8 | reserved[3]=0 | counter:u64 BE |
//       ciphertext | GCM tag[16]. Header authenticated as AAD.
// Nonce = random per-key 4-byte prefix || counter:u64 BE, counter starts at 1.
// Keys/prefixes are fresh per TLS connection, independent for each direction.
// No ciphertext replay (2048-packet sliding window), even if authentication fails.
class MediaCipher {
  public:
    static constexpr std::size_t kOverhead = 32;
    static constexpr std::size_t kMaxPlaintext = 1500;
    static constexpr std::uint8_t kServer = 1, kClient = 2;
    MediaCipher() = default;
    ~MediaCipher() { clear(); }
    MediaCipher(const MediaCipher&) = delete;
    MediaCipher& operator=(const MediaCipher&) = delete;
    bool generate(std::uint8_t direction);
    bool configure(std::string_view key, std::string_view prefix, std::uint8_t direction);
    std::string key_hex() const;
    std::string prefix_hex() const;
    void clear();
    bool seal(const std::uint8_t* plain, std::size_t size, std::vector<std::uint8_t>& out);
    bool open(const std::uint8_t* packet, std::size_t size, std::vector<std::uint8_t>& out);
  private:
    std::array<std::uint8_t, 32> key_{};
    std::array<std::uint8_t, 4> prefix_{};
    std::uint8_t direction_ = 0;
    std::uint64_t next_ = 1, highest_ = 0;
    std::bitset<2048> received_;
};
} // namespace metashare::security
