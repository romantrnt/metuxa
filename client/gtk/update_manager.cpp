#include "update_manager.hpp"
#include <curl/curl.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cerrno>
#include <climits>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <string_view>
#include <sys/file.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#ifndef METUXA_VERSION
#define METUXA_VERSION "0.4.0"
#endif
namespace metuxa::control {
namespace {
constexpr int current_code = 5, protocol = 5;
constexpr std::uint64_t max_manifest = 16384, max_linux = 512ull * 1024 * 1024, max_android = 256ull * 1024 * 1024;
constexpr char manifest_url[] = "https://github.com/romantrnt/metuxa/releases/latest/download/update-manifest.txt";
constexpr char release_root[] = "https://github.com/romantrnt/metuxa/releases/download/v";
constexpr char public_key[] = R"METUXA_KEY(
-----BEGIN PUBLIC KEY-----
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAmkkIZxm8V6MIGMhud4e+
Nfjc1I7V+3veW8mLPU+MVy1L2FOUs0i08+7f/ao7S6LcfwWBhRM/CLykmNKGN4jJ
+3I/ahmZw8ZjeLR0igqfvK+NLhV0+HXtm1I06TzOrYZKa3tkv7WGm0rm13oIE6cz
H37w/vm09Zefc5lNrRdbEZGvFONiOzg19zoJCh3p1tVCiIaylVgUzV4totvaWlcS
Lpyz6sisl7CKc74M6PC3KsyQMQ0gamuY1hSfFvd8WriZcsfCE9av7DQSV0Qmt0DK
M9f23X96VgNLSlAALG30xgeiIzS6B/FSibhqmEAGZj/sedwZ0bYBjWMnkgjiykbt
BlLyk8II5gXpf8oJvKimaWJZ8pG/NYKns/REWVERE0UytsYPdBMn0qDKA8ZmJTSZ
XgQQAEciW4CfhlKHlugwkMxA1TgdMOCbfwfbOTzDUPx6jeBM+arEaDrda3y7MvZS
JWmd15yr3AGb1KqvCiuiNvMBW/tTtwj81LIhbwuxuX26awtpZs3Qg0D1hivDCooM
1a/dmcob9Cfx3iSa/V203rKSCWX02w8SeHcAsaWjA+3ABYxxjVHgBrc3HU1RiTjh
xXyLNNRm7OGgmgULAiTvXrQ33UDPxHFJLRihuoZ9HWlAF1e39IegDjrytryqoNSb
DPv96t2e1B3rpDVVHluCBwkCAwEAAQ==
-----END PUBLIC KEY-----
)METUXA_KEY";
struct Fd {
    int value = -1;
    explicit Fd(int fd = -1) : value(fd) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};
bool fail(std::string& error, const char* message) { error = message; return false; }
bool stopped(const CancelCheck& check, std::string& error) {
    if (check && check()) { error = "Update cancelled."; return true; }
    return false;
}
bool decimal(std::string_view value, std::uint64_t& result, std::uint64_t limit, bool zero = false) {
    if (value.empty() || (value.size() > 1 && value.front() == '0')) return false;
    for (const char c : value) if (c < '0' || c > '9') return false;
    const auto parsed = std::from_chars(value.data(), value.data() + value.size(), result);
    return parsed.ec == std::errc{} && parsed.ptr == value.data() + value.size() && result <= limit && (zero || result > 0);
}
bool version_parts(std::string_view text, std::array<unsigned, 3>& parts) {
    for (unsigned i = 0; i < 3; ++i) {
        auto end = text.find('.');
        if ((i < 2 && end == text.npos) || (i == 2 && end != text.npos)) return false;
        std::uint64_t part = 0;
        if (!decimal(text.substr(0, end), part, 999, true)) return false;
        parts[i] = static_cast<unsigned>(part);
        if (i < 2) text.remove_prefix(end + 1);
    }
    return true;
}
bool hash_text(std::string_view value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    });
}
std::string hex(const unsigned char* data, std::size_t size) {
    constexpr char alphabet[] = "0123456789abcdef";
    std::string result(size * 2, '0');
    for (std::size_t i = 0; i < size; ++i) { result[2*i] = alphabet[data[i] >> 4]; result[2*i+1] = alphabet[data[i] & 15]; }
    return result;
}
bool write_all(int fd, const char* bytes, std::size_t length) {
    while (length) {
        auto written = write(fd, bytes, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        bytes += written; length -= static_cast<std::size_t>(written);
    }
    return true;
}
bool newer(const update_detail::Manifest& manifest, std::string& error) {
    if (!update_detail::compatible_manifest(manifest, error)) return false;
    if (manifest.info.version_code <= current_code || update_detail::compare_versions(manifest.info.version, METUXA_VERSION) <= 0)
        return fail(error, "The signed release is not newer than this application. Downgrades are blocked.");
    return true;
}
bool appimage_header(int fd) {
    std::array<unsigned char, 20> header{};
    return pread(fd, header.data(), header.size(), 0) == static_cast<ssize_t>(header.size()) &&
        header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' &&
        header[4] == 2 && header[5] == 1 && header[8] == 'A' && header[9] == 'I' && header[10] == 2 &&
        header[18] == 62 && header[19] == 0;
}
struct Target {
    Fd directory, image;
    std::string parent, name;
    struct stat original{};
};
bool open_target(const std::string& path, Target& target, std::string& error, bool lock) {
#if !defined(__x86_64__)
    return fail(error, "Automatic AppImage updates currently support x86_64 Linux only.");
#endif
    const auto slash = path.rfind('/');
    if (path.empty() || path.front() != '/' || slash == path.npos || slash + 1 == path.size())
        return fail(error, "Run the downloaded AppImage to update it. This installation needs a manual update.");
    target.name = path.substr(slash + 1);
    if (target.name == "." || target.name == ".." || target.name.size() > 180)
        return fail(error, "The AppImage filename cannot be used for automatic updates.");
    std::unique_ptr<char, decltype(&free)> resolved(realpath(path.substr(0, slash ? slash : 1).c_str(), nullptr), &free);
    if (!resolved) return fail(error, "The AppImage folder is unavailable.");
    target.parent = resolved.get();
    target.directory.value = open(target.parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    struct stat dir{};
    if (target.directory.value < 0 || fstat(target.directory.value, &dir) || dir.st_uid != getuid() || (dir.st_mode & 0022))
        return fail(error, "Move the AppImage to a folder you own that other users cannot modify, then try again.");
    if (faccessat(target.directory.value, ".", W_OK, AT_EACCESS) != 0)
        return fail(error, "The AppImage folder is not writable. Download and replace the file manually.");
    if (lock && flock(target.directory.value, LOCK_EX | LOCK_NB))
        return fail(error, "Another update is using this AppImage folder. Try again when it finishes.");
    target.image.value = openat(target.directory.value, target.name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (target.image.value < 0 || fstat(target.image.value, &target.original) || !S_ISREG(target.original.st_mode) ||
        target.original.st_uid != getuid() || target.original.st_nlink != 1 || (target.original.st_mode & (0022 | S_ISUID | S_ISGID)) ||
        !(target.original.st_mode & S_IXUSR) || !(target.original.st_mode & S_IWUSR) || !appimage_header(target.image.value))
        return fail(error, "The installed file must be your regular, executable x86_64 AppImage, without symlinks or hard links.");
    if (faccessat(target.directory.value, target.name.c_str(), W_OK, AT_EACCESS))
        return fail(error, "The AppImage is read-only. Download and replace it manually.");
    return true;
}
std::string current_appimage() { const auto* path = getenv("APPIMAGE"); return path ? path : ""; }
bool unchanged(const Target& target) {
    struct stat now{};
    return fstatat(target.directory.value, target.name.c_str(), &now, AT_SYMLINK_NOFOLLOW) == 0 &&
        now.st_dev == target.original.st_dev && now.st_ino == target.original.st_ino && now.st_size == target.original.st_size &&
        now.st_mtim.tv_sec == target.original.st_mtim.tv_sec && now.st_mtim.tv_nsec == target.original.st_mtim.tv_nsec &&
        now.st_ctim.tv_sec == target.original.st_ctim.tv_sec && now.st_ctim.tv_nsec == target.original.st_ctim.tv_nsec;
}
std::string random_suffix() {
    std::array<unsigned char, 16> bytes{};
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        auto count = getrandom(bytes.data() + offset, bytes.size() - offset, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return {};
        offset += static_cast<std::size_t>(count);
    }
    return hex(bytes.data(), bytes.size());
}
struct Temporary {
    int directory;
    Fd file;
    std::string name;
    explicit Temporary(int parent) : directory(parent) {}
    ~Temporary() { if (!name.empty()) unlinkat(directory, name.c_str(), 0); }
    bool create(std::string& error) {
        for (int attempt = 0; attempt < 8; ++attempt) {
            auto random = random_suffix();
            if (random.empty()) return fail(error, "Could not create a secure temporary filename.");
            name = ".metuxa-update-" + random;
            file.value = openat(directory, name.c_str(), O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (file.value >= 0) return true;
            name.clear();
            if (errno != EEXIST) break;
        }
        return fail(error, "Could not create the update file. Check folder permissions and free space.");
    }
};
struct Transfer {
    CURL* curl = nullptr;
    int fd = -1;
    std::string* text = nullptr;
    std::uint64_t maximum = 0, received = 0;
    std::size_t headers = 0;
    std::string location;
    bool bad_header = false, exceeded = false, write_failed = false;
    CancelCheck cancelled;
    ProgressCallback progress;
};
std::size_t write_response(char* bytes, std::size_t size, std::size_t count, void* opaque) {
    auto& transfer = *static_cast<Transfer*>(opaque);
    if (count && size > SIZE_MAX / count) return 0;
    const auto length = size * count;
    if (length > transfer.maximum - transfer.received) { transfer.exceeded = true; return 0; }
    transfer.received += length;
    long status = 0; curl_easy_getinfo(transfer.curl, CURLINFO_RESPONSE_CODE, &status);
    if (status != 200) {
        if (transfer.received > max_manifest) { transfer.exceeded = true; return 0; }
        return length;
    }
    if (transfer.cancelled && transfer.cancelled()) return 0;
    if (transfer.text) transfer.text->append(bytes, length);
    if (transfer.fd >= 0 && !write_all(transfer.fd, bytes, length)) { transfer.write_failed = true; return 0; }
    if (transfer.progress) transfer.progress(transfer.received, transfer.maximum);
    return length;
}
std::size_t response_header(char* bytes, std::size_t size, std::size_t count, void* opaque) {
    auto& transfer = *static_cast<Transfer*>(opaque);
    if (count && size > SIZE_MAX / count) return 0;
    const auto length = size * count;
    if (length > max_manifest - transfer.headers) { transfer.bad_header = true; return 0; }
    transfer.headers += length;
    std::string_view line(bytes, length);
    if (line.starts_with("HTTP/")) transfer.location.clear();
    if (length >= 9 && strncasecmp(bytes, "Location:", 9) == 0) {
        if (!transfer.location.empty()) { transfer.bad_header = true; return 0; }
        line.remove_prefix(9);
        while (!line.empty() && (line.front() == ' ' || line.front() == '\t')) line.remove_prefix(1);
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n' || line.back() == ' ' || line.back() == '\t')) line.remove_suffix(1);
        if (line.size() > 8192) { transfer.bad_header = true; return 0; }
        transfer.location = std::string(line);
    }
    return length;
}
int transfer_progress(void* opaque, curl_off_t, curl_off_t, curl_off_t, curl_off_t) {
    const auto& transfer = *static_cast<Transfer*>(opaque);
    return transfer.cancelled && transfer.cancelled() ? 1 : 0;
}
bool download(const std::string& initial, int fd, std::string* text, std::uint64_t maximum,
    std::string& error, const CancelCheck& cancelled, const ProgressCallback& progress) {
    static std::once_flag initialized;
    static bool ready = false;
    std::call_once(initialized, [] { ready = curl_global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK; });
    if (!ready) return fail(error, "HTTPS initialization failed.");
    std::string url = initial;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(fd >= 0 ? 600 : 90);
    for (int redirects = 0; redirects <= 5; ++redirects) {
        if (stopped(cancelled, error)) return false;
        const auto seconds_left = std::chrono::duration_cast<std::chrono::seconds>(deadline - std::chrono::steady_clock::now()).count();
        if (seconds_left <= 0) return fail(error, "The GitHub download timed out.");
        if (!update_detail::allowed_download_url(url)) return fail(error, "The update redirected outside the allowed GitHub HTTPS hosts.");
        std::unique_ptr<CURL, decltype(&curl_easy_cleanup)> curl(curl_easy_init(), &curl_easy_cleanup);
        if (!curl) return fail(error, "Could not start the HTTPS download.");
        Transfer transfer; transfer.curl = curl.get(); transfer.fd = fd; transfer.text = text;
        transfer.maximum = maximum; transfer.cancelled = cancelled; transfer.progress = progress;
        curl_easy_setopt(curl.get(), CURLOPT_URL, url.c_str());
        curl_easy_setopt(curl.get(), CURLOPT_PROTOCOLS_STR, "https");
        curl_easy_setopt(curl.get(), CURLOPT_REDIR_PROTOCOLS_STR, "https");
        curl_easy_setopt(curl.get(), CURLOPT_FOLLOWLOCATION, 0L);
        curl_easy_setopt(curl.get(), CURLOPT_NETRC, CURL_NETRC_IGNORED);
        curl_easy_setopt(curl.get(), CURLOPT_SSL_VERIFYPEER, 1L);
        curl_easy_setopt(curl.get(), CURLOPT_SSL_VERIFYHOST, 2L);
        curl_easy_setopt(curl.get(), CURLOPT_SSLVERSION, CURL_SSLVERSION_TLSv1_2);
        curl_easy_setopt(curl.get(), CURLOPT_CONNECTTIMEOUT, 15L);
        curl_easy_setopt(curl.get(), CURLOPT_TIMEOUT, static_cast<long>(seconds_left));
        curl_easy_setopt(curl.get(), CURLOPT_LOW_SPEED_LIMIT, 1024L);
        curl_easy_setopt(curl.get(), CURLOPT_LOW_SPEED_TIME, 30L);
        curl_easy_setopt(curl.get(), CURLOPT_MAXFILESIZE_LARGE, static_cast<curl_off_t>(maximum));
        curl_easy_setopt(curl.get(), CURLOPT_NOSIGNAL, 1L);
        curl_easy_setopt(curl.get(), CURLOPT_USERAGENT, "metuxa/" METUXA_VERSION " updater");
        curl_easy_setopt(curl.get(), CURLOPT_WRITEFUNCTION, &write_response);
        curl_easy_setopt(curl.get(), CURLOPT_WRITEDATA, &transfer);
        curl_easy_setopt(curl.get(), CURLOPT_HEADERFUNCTION, &response_header);
        curl_easy_setopt(curl.get(), CURLOPT_HEADERDATA, &transfer);
        curl_easy_setopt(curl.get(), CURLOPT_XFERINFOFUNCTION, &transfer_progress);
        curl_easy_setopt(curl.get(), CURLOPT_XFERINFODATA, &transfer);
        curl_easy_setopt(curl.get(), CURLOPT_NOPROGRESS, 0L);
        const auto result = curl_easy_perform(curl.get());
        if (stopped(cancelled, error)) return false;
        if (result != CURLE_OK) {
            if (transfer.exceeded || result == CURLE_FILESIZE_EXCEEDED) return fail(error, "The download exceeds the signed size limit.");
            if (transfer.write_failed) return fail(error, "Could not save the download. Check available disk space.");
            if (transfer.bad_header) return fail(error, "GitHub returned invalid or oversized response headers.");
            error = "GitHub HTTPS download failed: " + std::string(curl_easy_strerror(result)); return false;
        }
        long status = 0; curl_easy_getinfo(curl.get(), CURLINFO_RESPONSE_CODE, &status);
        if (status == 200) return true;
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            if (transfer.location.empty() || redirects == 5) return fail(error, "The GitHub download has too many or invalid redirects.");
            url = transfer.location;
            continue;
        }
        if (status == 404) return fail(error, "No signed update is published on GitHub yet.");
        if (status == 403 || status == 429) return fail(error, "GitHub is limiting requests. Try again later.");
        error = "GitHub returned HTTP " + std::to_string(status) + "."; return false;
    }
    return fail(error, "The GitHub download has too many redirects.");
}
bool hash_and_copy(int input, int output, const UpdateInfo& info, std::string& error,
    const CancelCheck& cancelled, const ProgressCallback& progress) {
    if (lseek(input, 0, SEEK_SET) < 0) return fail(error, "Could not read the staged download.");
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> digest(EVP_MD_CTX_new(), &EVP_MD_CTX_free);
    if (!digest || EVP_DigestInit_ex(digest.get(), EVP_sha256(), nullptr) != 1) return fail(error, "SHA-256 initialization failed.");
    std::array<char, 65536> bytes{};
    std::uint64_t total = 0;
    for (;;) {
        if (stopped(cancelled, error)) return false;
        auto length = read(input, bytes.data(), bytes.size());
        if (length < 0 && errno == EINTR) continue;
        if (length < 0) return fail(error, "Could not read the staged AppImage.");
        if (length == 0) break;
        if (static_cast<std::uint64_t>(length) > info.linux_size - total) return fail(error, "The AppImage size does not match its signed manifest.");
        total += static_cast<std::uint64_t>(length);
        if (EVP_DigestUpdate(digest.get(), bytes.data(), static_cast<std::size_t>(length)) != 1) return fail(error, "SHA-256 verification failed.");
        if (output >= 0 && !write_all(output, bytes.data(), static_cast<std::size_t>(length))) return fail(error, "Could not prepare the AppImage. Check available disk space.");
        if (progress) progress(total, info.linux_size);
    }
    std::array<unsigned char, EVP_MAX_MD_SIZE> result{}; unsigned length = 0;
    if (total != info.linux_size || EVP_DigestFinal_ex(digest.get(), result.data(), &length) != 1 ||
        length != 32 || hex(result.data(), length) != info.linux_sha256)
        return fail(error, "The AppImage size or SHA-256 does not match its signed manifest.");
    if (!appimage_header(input)) return fail(error, "The signed download is not an x86_64 type-2 AppImage.");
    return true;
}
bool receipt_floor(const Target& target, const update_detail::Manifest& candidate, const std::string& key, std::string& error) {
    const std::string name = target.name + ".update-manifest";
    Fd receipt(openat(target.directory.value, name.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (receipt.value < 0) {
        if (errno == ENOENT) return true;
        return fail(error, "Could not safely read the previous update record.");
    }
    struct stat st{};
    if (fstat(receipt.value, &st) || !S_ISREG(st.st_mode) || st.st_uid != getuid() || st.st_nlink != 1 ||
        (st.st_mode & 0077) || st.st_size <= 0 || static_cast<std::uint64_t>(st.st_size) > max_manifest)
        return fail(error, "The previous update record has unsafe permissions or an invalid size.");
    std::string document(static_cast<std::size_t>(st.st_size), '\0');
    std::size_t offset = 0;
    while (offset < document.size()) {
        auto length = read(receipt.value, document.data() + offset, document.size() - offset);
        if (length < 0 && errno == EINTR) continue;
        if (length <= 0) return fail(error, "Could not read the previous update record.");
        offset += static_cast<std::size_t>(length);
    }
    update_detail::Manifest previous;
    if (!update_detail::parse_signed_manifest(document, key, previous, error)) return false;
    const int ordering = update_detail::compare_versions(candidate.info.version, previous.info.version);
    if (candidate.info.version_code < previous.info.version_code || ordering < 0 ||
        (candidate.info.version_code == previous.info.version_code && candidate.info.signed_manifest != previous.info.signed_manifest) ||
        (ordering == 0 && candidate.info.signed_manifest != previous.info.signed_manifest))
        return fail(error, "A newer signed update was already prepared here. Replayed and downgraded releases are blocked.");
    return true;
}
bool install_impl(const std::string& document, const std::string& key, const std::string& staging,
    const std::string& path, std::string& backup_path, std::string& error,
    const CancelCheck& cancelled, const ProgressCallback& progress) {
    error.clear(); backup_path.clear();
    update_detail::Manifest manifest;
    if (!update_detail::parse_signed_manifest(document, key, manifest, error) || !newer(manifest, error)) return false;
    Target target;
    if (!open_target(path, target, error, true) || !receipt_floor(target, manifest, key, error) || stopped(cancelled, error)) return false;
    Fd staged(open(staging.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    struct stat stage_stat{};
    if (staged.value < 0 || fstat(staged.value, &stage_stat) || !S_ISREG(stage_stat.st_mode) ||
        stage_stat.st_uid != getuid() || stage_stat.st_nlink != 1 || (stage_stat.st_mode & 0077) ||
        stage_stat.st_size < 0 || static_cast<std::uint64_t>(stage_stat.st_size) != manifest.info.linux_size)
        return fail(error, "The staged AppImage is missing, changed, or has unsafe permissions. Download it again.");
    Temporary replacement(target.directory.value);
    if (!replacement.create(error) || !hash_and_copy(staged.value, replacement.file.value, manifest.info, error, cancelled, progress)) return false;
    if (fchmod(replacement.file.value, target.original.st_mode & 0755) || fsync(replacement.file.value))
        return fail(error, "Could not safely save the replacement AppImage.");
    if (!unchanged(target)) return fail(error, "The installed AppImage changed during the update. Restart it and try again.");
    if (stopped(cancelled, error)) return false;
    // Persist the signed high-water mark before replacing the executable. A crash
    // can leave a retryable release recorded, but can never permit a downgrade.
    Temporary receipt(target.directory.value);
    if (!receipt.create(error) || !write_all(receipt.file.value, document.data(), document.size()) || fsync(receipt.file.value))
        return fail(error, "Could not save the signed update record.");
    // Copy the old inode rather than hard-linking it: a crash before the final
    // rename must not leave the live AppImage with nlink=2 and block retries.
    Temporary backup_copy(target.directory.value);
    if (!backup_copy.create(error)) return false;
    if (target.original.st_size <= 0 || static_cast<std::uint64_t>(target.original.st_size) > max_linux)
        return fail(error, "The installed AppImage exceeds the supported backup size.");
    std::array<char, 65536> backup_bytes{};
    std::uint64_t copied = 0;
    while (copied < static_cast<std::uint64_t>(target.original.st_size)) {
        if (stopped(cancelled, error)) return false;
        const auto wanted = std::min<std::uint64_t>(backup_bytes.size(), static_cast<std::uint64_t>(target.original.st_size) - copied);
        const auto count = pread(target.image.value, backup_bytes.data(), static_cast<std::size_t>(wanted), static_cast<off_t>(copied));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || !write_all(backup_copy.file.value, backup_bytes.data(), static_cast<std::size_t>(count)))
            return fail(error, "Could not retain the old AppImage. Check available disk space.");
        copied += static_cast<std::uint64_t>(count);
    }
    if (fchmod(backup_copy.file.value, target.original.st_mode & 0755) || fsync(backup_copy.file.value) || !unchanged(target))
        return fail(error, "Could not save an unchanged backup of the installed AppImage.");
    if (stopped(cancelled, error)) return false;
    const auto backup = target.name + ".backup-" + random_suffix();
    // Publish a complete backup without overwriting any existing path. This
    // hard link points only to the private copy, never to the live AppImage.
    if (backup.ends_with('-') || linkat(target.directory.value, backup_copy.name.c_str(), target.directory.value, backup.c_str(), 0))
        return fail(error, "Could not retain the old AppImage. The installed file was not replaced.");
    if (unlinkat(target.directory.value, backup_copy.name.c_str(), 0)) {
        unlinkat(target.directory.value, backup.c_str(), 0);
        return fail(error, "Could not finish saving the backup. The installed file was not replaced.");
    }
    backup_copy.name.clear();
    const auto receipt_name = target.name + ".update-manifest";
    if (renameat(target.directory.value, receipt.name.c_str(), target.directory.value, receipt_name.c_str()) || fsync(target.directory.value)) {
        unlinkat(target.directory.value, backup.c_str(), 0);
        return fail(error, "Could not persist the signed update record. The AppImage was not replaced.");
    }
    receipt.name.clear();
    if (!unchanged(target) ||
        renameat(target.directory.value, replacement.name.c_str(), target.directory.value, target.name.c_str())) {
        unlinkat(target.directory.value, backup.c_str(), 0);
        return fail(error, "Could not atomically replace the AppImage. The previous file remains available.");
    }
    replacement.name.clear();
    backup_path = target.parent + "/" + backup;
    if (fsync(target.directory.value)) error = "The update is installed, but the filesystem could not confirm durable storage. Keep the backup.";
    return true;
}
} // namespace
namespace update_detail {
std::string pinned_public_key() { return public_key; }
bool parse_signed_manifest(const std::string& document, const std::string& key_text, Manifest& output, std::string& error) {
    output = {}; error.clear();
    const auto newline = document.find('\n');
    if (document.empty() || document.size() > max_manifest || newline == document.npos || newline > 2048 || newline == 0)
        return fail(error, "The update manifest is missing or oversized.");
    const std::string encoded = document.substr(0, newline);
    if (encoded.size() % 4 || !std::all_of(encoded.begin(), encoded.end(), [](char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
    })) return fail(error, "The manifest signature is not canonical base64.");
    std::vector<unsigned char> signature(encoded.size());
    int decoded = EVP_DecodeBlock(signature.data(), reinterpret_cast<const unsigned char*>(encoded.data()), static_cast<int>(encoded.size()));
    if (decoded <= 0) return fail(error, "The manifest signature is invalid.");
    if (encoded.ends_with('=')) --decoded;
    if (encoded.ends_with("==")) --decoded;
    if (decoded <= 0) return fail(error, "The manifest signature is invalid.");
    signature.resize(static_cast<std::size_t>(decoded));
    std::string canonical(4 * ((signature.size() + 2) / 3) + 1, '\0');
    const int canonical_size = EVP_EncodeBlock(reinterpret_cast<unsigned char*>(canonical.data()), signature.data(), decoded);
    canonical.resize(static_cast<std::size_t>(canonical_size));
    if (canonical != encoded) return fail(error, "The manifest signature is not canonical base64.");
    std::unique_ptr<BIO, decltype(&BIO_free)> bio(BIO_new_mem_buf(key_text.data(), static_cast<int>(key_text.size())), &BIO_free);
    std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(bio ? PEM_read_bio_PUBKEY(bio.get(), nullptr, nullptr, nullptr) : nullptr, &EVP_PKEY_free);
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> context(EVP_MD_CTX_new(), &EVP_MD_CTX_free);
    EVP_PKEY_CTX* key_context = nullptr;
    const std::string_view payload(document.data() + newline + 1, document.size() - newline - 1);
    if (!key || EVP_PKEY_base_id(key.get()) != EVP_PKEY_RSA || EVP_PKEY_bits(key.get()) < 2048 ||
        signature.size() != static_cast<std::size_t>(EVP_PKEY_size(key.get())) || !context ||
        EVP_DigestVerifyInit(context.get(), &key_context, EVP_sha256(), nullptr, key.get()) != 1 ||
        EVP_PKEY_CTX_set_rsa_padding(key_context, RSA_PKCS1_PADDING) <= 0 ||
        EVP_DigestVerify(context.get(), signature.data(), signature.size(), reinterpret_cast<const unsigned char*>(payload.data()), payload.size()) != 1)
        return fail(error, "The update signature does not match the trusted metuxa release key.");
    for (const unsigned char byte : payload) if ((byte < 32 && byte != '\n') || byte > 126)
        return fail(error, "The signed manifest contains unsupported characters.");
    constexpr std::array<std::string_view, 9> prefixes{"metuxa-update-v1", "version=", "version_code=", "protocol_min=", "protocol_max=",
        "android_size=", "android_sha256=", "linux_size=", "linux_sha256="};
    std::array<std::string_view, 9> values{};
    std::string_view remaining = payload;
    for (std::size_t i = 0; i < prefixes.size(); ++i) {
        const auto end = remaining.find('\n');
        if (end == remaining.npos) return fail(error, "The signed manifest has missing or unordered fields.");
        const auto line = remaining.substr(0, end);
        if (!line.starts_with(prefixes[i]) || (i == 0 && line != prefixes[i]))
            return fail(error, "The signed manifest has missing or unordered fields.");
        values[i] = line.substr(prefixes[i].size()); remaining.remove_prefix(end + 1);
    }
    if (!remaining.empty()) return fail(error, "The signed manifest contains extra fields or trailing data.");
    Manifest parsed; std::array<unsigned, 3> version{}; std::uint64_t code = 0, low = 0, high = 0;
    if (!version_parts(values[1], version) || !decimal(values[2], code, INT_MAX) || !decimal(values[3], low, INT_MAX) ||
        !decimal(values[4], high, INT_MAX) || low > high || !decimal(values[5], parsed.android_size, max_android) ||
        !decimal(values[7], parsed.info.linux_size, max_linux) || !hash_text(values[6]) || !hash_text(values[8]))
        return fail(error, "The signed manifest contains invalid version, protocol, size, or hash values.");
    parsed.info.version = values[1]; parsed.info.version_code = static_cast<int>(code);
    parsed.protocol_min = static_cast<int>(low); parsed.protocol_max = static_cast<int>(high);
    parsed.android_sha256 = values[6]; parsed.info.linux_sha256 = values[8]; parsed.info.signed_manifest = document;
    parsed.info.release_url = "https://github.com/romantrnt/metuxa/releases/tag/v" + parsed.info.version;
    output = std::move(parsed); return true;
}
int compare_versions(const std::string& left, const std::string& right) {
    std::array<unsigned, 3> a{}, b{};
    if (!version_parts(left, a) || !version_parts(right, b)) return -2;
    return a < b ? -1 : (a > b ? 1 : 0);
}
bool compatible_manifest(const Manifest& manifest, std::string& error) {
    if (manifest.protocol_min > protocol || manifest.protocol_max < protocol)
        return fail(error, "This update is incompatible with protocol 5. Update the paired devices manually together.");
    return true;
}
bool allowed_download_url(const std::string& url) {
    if (url.size() > 8192 || url.find_first_of("\r\n\t\\") != url.npos) return false;
    std::unique_ptr<CURLU, decltype(&curl_url_cleanup)> parsed(curl_url(), &curl_url_cleanup);
    if (!parsed || curl_url_set(parsed.get(), CURLUPART_URL, url.c_str(), CURLU_DISALLOW_USER)) return false;
    auto part = [&](CURLUPart which) {
        char* value = nullptr; std::string result;
        if (curl_url_get(parsed.get(), which, &value, 0) == CURLUE_OK) { result = value; curl_free(value); }
        return result;
    };
    const auto host = part(CURLUPART_HOST), port = part(CURLUPART_PORT);
    return part(CURLUPART_SCHEME) == "https" && (port.empty() || port == "443") && part(CURLUPART_FRAGMENT).empty() &&
        (host == "github.com" || host == "release-assets.githubusercontent.com" || host == "objects.githubusercontent.com");
}
#ifdef METUXA_UPDATE_TESTING
bool install_for_test(const std::string& document, const std::string& key, const std::string& staging,
    const std::string& target, std::string& backup, std::string& error, CancelCheck cancelled) {
    return install_impl(document, key, staging, target, backup, error, cancelled, {});
}
#endif
} // namespace update_detail
UpdateCheck check_for_update(UpdateInfo& info, std::string& error, CancelCheck cancelled) {
    info = {}; error.clear(); std::string document;
    if (!download(manifest_url, -1, &document, max_manifest, error, cancelled, {})) return UpdateCheck::failed;
    update_detail::Manifest manifest;
    if (!update_detail::parse_signed_manifest(document, public_key, manifest, error) || !update_detail::compatible_manifest(manifest, error)) return UpdateCheck::failed;
    const int ordering = update_detail::compare_versions(manifest.info.version, METUXA_VERSION);
    if (ordering == 0 && manifest.info.version_code == current_code) { info = manifest.info; return UpdateCheck::current; }
    if (!newer(manifest, error)) return UpdateCheck::failed;
    info = manifest.info; return UpdateCheck::available;
}
bool appimage_update_supported(std::string& error) {
    error.clear(); Target target; return open_target(current_appimage(), target, error, false);
}
bool download_appimage_update(const UpdateInfo& info, std::string& staging_path, std::string& error,
    CancelCheck cancelled, ProgressCallback progress) {
    staging_path.clear(); error.clear(); update_detail::Manifest manifest;
    if (!update_detail::parse_signed_manifest(info.signed_manifest, public_key, manifest, error) || !newer(manifest, error) || stopped(cancelled, error)) return false;
    char directory[] = "/tmp/metuxa-update-XXXXXX";
    if (!mkdtemp(directory)) return fail(error, "Could not create a private download folder.");
    const std::string path = std::string(directory) + "/asset.AppImage";
    Fd file(open(path.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600));
    const auto& verified = manifest.info;
    const std::string url = std::string(release_root) + verified.version + "/metuxa-" + verified.version + "-x86_64.AppImage";
    bool success = file.value >= 0 && download(url, file.value, nullptr, verified.linux_size, error, cancelled, progress) &&
        hash_and_copy(file.value, -1, verified, error, cancelled, {}) && fsync(file.value) == 0;
    if (!success) {
        if (error.empty()) error = "Could not save the staged download. Check available disk space.";
        unlink(path.c_str()); rmdir(directory); return false;
    }
    staging_path = path; return true;
}
bool install_staged_appimage_update(const UpdateInfo& info, const std::string& staging_path,
    std::string& backup_path, std::string& error, CancelCheck cancelled, ProgressCallback progress) {
    return install_impl(info.signed_manifest, public_key, staging_path, current_appimage(), backup_path, error, cancelled, progress);
}
bool install_appimage_update(const UpdateInfo& info, std::string& backup_path, std::string& error,
    CancelCheck cancelled, ProgressCallback progress) {
    std::string staging;
    if (!appimage_update_supported(error) || !download_appimage_update(info, staging, error, cancelled, progress)) return false;
    const bool result = install_staged_appimage_update(info, staging, backup_path, error, cancelled, progress);
    discard_staged_appimage_update(staging); return result;
}
void discard_staged_appimage_update(const std::string& path) {
    const std::string prefix = "/tmp/metuxa-update-", suffix = "/asset.AppImage";
    if (!path.starts_with(prefix) || !path.ends_with(suffix)) return;
    const std::string directory = path.substr(0, path.size() - suffix.size());
    if (directory.size() != prefix.size() + 6 || directory.find('/', prefix.size()) != directory.npos) return;
    Fd parent(open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    struct stat st{};
    if (parent.value < 0 || fstat(parent.value, &st) || st.st_uid != getuid() || (st.st_mode & 0077)) return;
    if (unlinkat(parent.value, "asset.AppImage", 0) == 0) rmdir(directory.c_str());
}
} // namespace metuxa::control
