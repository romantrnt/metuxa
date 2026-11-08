#include "update_manager.hpp"
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <array>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <memory>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
using namespace metuxa::control;
using namespace metuxa::control::update_detail;
namespace {
unsigned checks = 0;
void check(bool condition, const std::string& what) {
    ++checks;
    if (!condition) { std::cerr << "FAIL: " << what << '\n'; std::exit(1); }
}
struct Signer {
    EVP_PKEY* key = nullptr;
    std::string public_key;
    Signer() {
        std::unique_ptr<EVP_PKEY_CTX, decltype(&EVP_PKEY_CTX_free)> ctx(EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, nullptr), &EVP_PKEY_CTX_free);
        check(ctx && EVP_PKEY_keygen_init(ctx.get()) > 0 && EVP_PKEY_CTX_set_rsa_keygen_bits(ctx.get(), 2048) > 0 &&
            EVP_PKEY_keygen(ctx.get(), &key) > 0, "create ephemeral test signing identity");
        BIO* bio = BIO_new(BIO_s_mem()); check(bio && PEM_write_bio_PUBKEY(bio, key) == 1, "export test public key");
        char* bytes = nullptr; auto length = BIO_get_mem_data(bio, &bytes);
        public_key.assign(bytes, static_cast<std::size_t>(length)); BIO_free(bio);
    }
    ~Signer() { EVP_PKEY_free(key); }
    std::string sign(const std::string& payload) {
        std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(), &EVP_MD_CTX_free);
        EVP_PKEY_CTX* key_context = nullptr;
        check(context && EVP_DigestSignInit(context.get(), &key_context, EVP_sha256(), nullptr, key) == 1 &&
            EVP_PKEY_CTX_set_rsa_padding(key_context, RSA_PKCS1_PADDING) > 0, "initialize test signature");
        std::size_t size = 0;
        check(EVP_DigestSign(context.get(), nullptr, &size, reinterpret_cast<const unsigned char*>(payload.data()), payload.size()) == 1, "size test signature");
        std::string signature(size, '\0');
        check(EVP_DigestSign(context.get(), reinterpret_cast<unsigned char*>(signature.data()), &size,
            reinterpret_cast<const unsigned char*>(payload.data()), payload.size()) == 1, "sign fixture");
        signature.resize(size);
        std::string encoded(4 * ((size + 2) / 3) + 1, '\0');
        encoded.resize(static_cast<std::size_t>(EVP_EncodeBlock(reinterpret_cast<unsigned char*>(encoded.data()),
            reinterpret_cast<const unsigned char*>(signature.data()), static_cast<int>(size))));
        return encoded + "\n" + payload;
    }
};
std::string sha256(const std::string& data) {
    std::array<unsigned char, 32> digest{}; unsigned size = 0;
    check(EVP_Digest(data.data(), data.size(), digest.data(), &size, EVP_sha256(), nullptr) == 1 && size == 32, "hash test asset");
    constexpr char alphabet[] = "0123456789abcdef"; std::string text;
    for (const auto byte : digest) { text += alphabet[byte >> 4]; text += alphabet[byte & 15]; }
    return text;
}
std::string asset(char fill = 'q') {
    std::string result(4096, fill);
    result[0] = '\x7f'; result[1] = 'E'; result[2] = 'L'; result[3] = 'F'; result[4] = 2; result[5] = 1;
    result[8] = 'A'; result[9] = 'I'; result[10] = 2; result[18] = 62; result[19] = 0;
    return result;
}
std::string payload(const std::string& content, const std::string& version = "0.5.0", int code = 6) {
    return "metuxa-update-v1\nversion=" + version + "\nversion_code=" + std::to_string(code) +
        "\nprotocol_min=5\nprotocol_max=5\nandroid_size=123\nandroid_sha256=" + std::string(64, 'a') +
        "\nlinux_size=" + std::to_string(content.size()) + "\nlinux_sha256=" + sha256(content) + "\n";
}
std::string replace(std::string text, const std::string& old, const std::string& value) {
    auto position = text.find(old); check(position != text.npos, "fixture substring present"); text.replace(position, old.size(), value); return text;
}
void write_file(const std::string& path, const std::string& contents, mode_t mode) {
    std::ofstream file(path, std::ios::binary); file.write(contents.data(), static_cast<std::streamsize>(contents.size())); file.close();
    check(file.good() && chmod(path.c_str(), mode) == 0, "write private fixture");
}
std::string read_file(const std::string& path) {
    std::ifstream file(path, std::ios::binary); return {std::istreambuf_iterator<char>(file), {}};
}
struct Fixture {
    std::string directory, target, stage;
    std::string previous = asset('o'), replacement = asset('n');
    Fixture() {
        char temporary[] = "/tmp/metuxa-updater-tests-XXXXXX";
        auto* created = mkdtemp(temporary); check(created, "private test folder"); directory = created;
        target = directory + "/metuxa.AppImage"; stage = directory + "/staged.AppImage";
        write_file(target, previous, 0755); write_file(stage, replacement, 0600);
    }
    ~Fixture() { chmod(directory.c_str(), 0700); std::filesystem::remove_all(directory); }
};
}
int main() {
    Signer signer, other;
    const auto content = asset(); const auto valid_payload = payload(content); const auto document = signer.sign(valid_payload);
    std::string error, backup; Manifest manifest;
    check(parse_signed_manifest(document, signer.public_key, manifest, error), "valid RSA SHA256 signed manifest: " + error);
    check(manifest.info.version == "0.5.0" && manifest.info.version_code == 6 && manifest.info.linux_size == content.size() &&
        manifest.protocol_min == 5 && manifest.protocol_max == 5 && manifest.info.signed_manifest == document, "parsed exact metadata");
    check(compatible_manifest(manifest, error), "protocol 5 accepted");
    check(!parse_signed_manifest(document, pinned_public_key(), manifest, error), "production key rejects ephemeral signer");
    check(!parse_signed_manifest(document, other.public_key, manifest, error), "wrong signer rejected");
    check(!parse_signed_manifest(replace(document, "version=0.5.0", "version=0.6.0"), signer.public_key, manifest, error), "tampered payload rejected");
    check(!parse_signed_manifest(document + "\n", signer.public_key, manifest, error), "unsigned trailing data rejected");
    check(!parse_signed_manifest(std::string(17000, 'a'), signer.public_key, manifest, error), "oversized manifest rejected");
    check(!parse_signed_manifest("====\n" + valid_payload, signer.public_key, manifest, error), "invalid base64 rejected");
    check(!parse_signed_manifest(replace(document, "\n", "\r\n"), signer.public_key, manifest, error), "signature CRLF rejected");
    const std::array<std::pair<std::string, std::string>, 16> invalid_fields{{
        {"version=0.5.0", "version=00.5.0"}, {"version=0.5.0", "version=0.5.1000"},
        {"version=0.5.0", "version=0.5.0-beta"}, {"version_code=6", "version_code=06"},
        {"version_code=6", "version_code=0"}, {"version_code=6", "version_code=2147483648"},
        {"version_code=6", "version_code=+6"}, {"protocol_min=5", "protocol_min=6"},
        {"android_size=123", "android_size=268435457"}, {"android_size=123", "android_size=0"},
        {"linux_size=4096", "linux_size=536870913"}, {"linux_size=4096", "linux_size=04096"},
        {"linux_size=4096", "linux_size=18446744073709551616"}, {"android_sha256=aaaa", "android_sha256=AAAA"},
        {"protocol_max=5", "protocol_max=5\r"}, {"metuxa-update-v1", "metuxa-update-v2"}
    }};
    for (const auto& [old, value] : invalid_fields)
        check(!parse_signed_manifest(signer.sign(replace(valid_payload, old, value)), signer.public_key, manifest, error), "reject signed invalid field " + value);
    check(!parse_signed_manifest(signer.sign(valid_payload + "extra=1\n"), signer.public_key, manifest, error), "unknown field rejected");
    check(!parse_signed_manifest(signer.sign(valid_payload + "\n"), signer.public_key, manifest, error), "signed trailing line rejected");
    check(!parse_signed_manifest(signer.sign(valid_payload.substr(0, valid_payload.size()-1)), signer.public_key, manifest, error), "required final LF");
    check(!parse_signed_manifest(signer.sign(replace(valid_payload, "protocol_min=5\nprotocol_max=5", "protocol_max=5\nprotocol_min=5")), signer.public_key, manifest, error), "reordered fields rejected");
    check(!parse_signed_manifest(signer.sign(replace(valid_payload, "protocol_max=5", "protocol_min=5")), signer.public_key, manifest, error), "duplicate fields rejected");
    check(parse_signed_manifest(signer.sign(replace(valid_payload, "protocol_min=5\nprotocol_max=5", "protocol_min=6\nprotocol_max=7")), signer.public_key, manifest, error) &&
        !compatible_manifest(manifest, error), "signed incompatible protocol blocked");
    check(compare_versions("0.10.0", "0.9.99") > 0 && compare_versions("1.0.0", "0.999.999") > 0 &&
        compare_versions("0.5.0", "0.5.0") == 0 && compare_versions("0.4.9", "0.5.0") < 0 &&
        compare_versions("00.3.0", "0.5.0") == -2, "canonical numeric version comparison");
    for (const auto* url : {"https://github.com/romantrnt/metuxa/releases/latest/download/update-manifest.txt", "https://release-assets.githubusercontent.com/release?token=abc", "https://objects.githubusercontent.com/a", "https://github.com:443/a"})
        check(allowed_download_url(url), std::string("allow GitHub HTTPS: ") + url);
    for (const auto* url : {"http://github.com/a", "https://github.com.evil.invalid/a", "https://evil.invalid/github.com", "file:///etc/passwd", "https://github.com:444/a", "https://name@github.com/a", "https://github.com/a#fragment", "https://github.com\\@evil.invalid/a", "https://github.com/a\r\nInjected:x", "https://127.0.0.1/a", "https://raw.githubusercontent.com/a"})
        check(!allowed_download_url(url), std::string("block unsafe URL: ") + url);
    {
        Fixture f;
        auto signed_release = signer.sign(payload(f.replacement));
        check(install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "atomic signed installation: " + error);
        check(read_file(f.target) == f.replacement && read_file(backup) == f.previous, "replacement and rollback bytes exact");
        struct stat replaced_stat{}, backup_stat{};
        check(stat(f.target.c_str(), &replaced_stat) == 0 && stat(backup.c_str(), &backup_stat) == 0 &&
            replaced_stat.st_ino != backup_stat.st_ino && backup_stat.st_nlink == 1, "backup is an independent regular file");
        check(read_file(f.target + ".update-manifest") == signed_release, "signed anti-replay receipt persisted");
        struct stat st{}; check(stat(f.target.c_str(), &st) == 0 && (st.st_mode & 0777) == 0755 && st.st_nlink == 1, "replacement executable, no hardlink");
        check(stat((f.target + ".update-manifest").c_str(), &st) == 0 && (st.st_mode & 0777) == 0600, "receipt private");
        check(!install_for_test(signer.sign(payload(f.replacement, "0.4.9", 6)), signer.public_key, f.stage, f.target, backup, error), "receipt blocks lower version and reused code");
        check(read_file(f.target) == f.replacement, "replay rejection preserves installed image");
        check(!install_for_test(signer.sign(payload(f.replacement, "0.6.0", 6)), signer.public_key, f.stage, f.target, backup, error), "receipt blocks reused code with higher version");
        check(install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "same signed candidate retry is safe: " + error);
        for (auto& item : std::filesystem::directory_iterator(f.directory)) check(!item.path().filename().string().starts_with(".metuxa-update-"), "no temporary replacement leftovers");
    }
    {
        Fixture f;
        check(!install_for_test(signer.sign(payload(f.replacement, "0.2.0", 3)), signer.public_key, f.stage, f.target, backup, error), "running-version downgrade blocked");
        check(!install_for_test(signer.sign(payload(f.replacement, "0.9.0", 5)), signer.public_key, f.stage, f.target, backup, error), "running-version code floor enforced");
        check(!install_for_test(signer.sign(payload(f.replacement)), signer.public_key, f.stage, f.target, backup, error, [] { return true; }), "cancel before installation");
        check(read_file(f.target) == f.previous && !std::filesystem::exists(f.target + ".update-manifest"), "cancel preserves old file and receipt state");
    }
    {
        Fixture f; auto signed_release = signer.sign(payload(f.replacement));
        write_file(f.stage, asset('x'), 0600);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "same-size corrupted download rejected");
        check(read_file(f.target) == f.previous, "hash rejection preserves target");
        write_file(f.stage, f.replacement + 'x', 0600);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "oversized staging rejected");
        write_file(f.stage, f.replacement.substr(0, 100), 0600);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "truncated staging rejected");
    }
    {
        Fixture f; auto invalid = f.replacement; invalid[18] = 183; write_file(f.stage, invalid, 0600);
        check(!install_for_test(signer.sign(payload(invalid)), signer.public_key, f.stage, f.target, backup, error), "signed wrong architecture blocked");
        invalid = f.replacement; invalid[8] = 'x'; write_file(f.stage, invalid, 0600);
        check(!install_for_test(signer.sign(payload(invalid)), signer.public_key, f.stage, f.target, backup, error), "signed non-AppImage blocked");
    }
    {
        Fixture f; auto signed_release = signer.sign(payload(f.replacement));
        chmod(f.target.c_str(), 0555);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "read-only installed AppImage blocked"); chmod(f.target.c_str(), 0755);
        chmod(f.directory.c_str(), 0777);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "shared writable directory blocked"); chmod(f.directory.c_str(), 0700);
        const auto link = f.directory + "/link.AppImage"; check(symlink(f.target.c_str(), link.c_str()) == 0, "create test symlink");
        check(!install_for_test(signed_release, signer.public_key, f.stage, link, backup, error), "symlink target blocked"); unlink(link.c_str());
        check(symlink(f.stage.c_str(), link.c_str()) == 0, "create staging symlink");
        check(!install_for_test(signed_release, signer.public_key, link, f.target, backup, error), "symlink staged file blocked"); unlink(link.c_str());
        check(linkat(AT_FDCWD, f.target.c_str(), AT_FDCWD, link.c_str(), 0) == 0, "create test hardlink");
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "hardlinked target blocked"); unlink(link.c_str());
        write_file(f.target + ".update-manifest", "broken", 0600);
        check(!install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error), "corrupt receipt fails closed");
        check(read_file(f.target) == f.previous, "unsafe path tests preserved original file");
    }
    {
        Fixture f; const auto signed_release = signer.sign(payload(f.replacement));
        write_file(f.target + ".update-manifest", signed_release, 0600);
        write_file(f.target + ".backup-crash", f.previous, 0755);
        check(install_for_test(signed_release, signer.public_key, f.stage, f.target, backup, error),
            "retry after crash between durable receipt and replacement: " + error);
        check(read_file(f.target) == f.replacement && read_file(f.target + ".backup-crash") == f.previous,
            "crash recovery preserves earlier backup");
    }
    unsetenv("APPIMAGE"); check(!appimage_update_supported(error) && !error.empty(), "native installation explains manual update");
    std::cout << checks << " updater checks passed; no network or downloaded code execution.\n";
}
