#pragma once
#include <cstdint>
#include <functional>
#include <string>

namespace metuxa::control {
using CancelCheck = std::function<bool()>;
using ProgressCallback = std::function<void(std::uint64_t, std::uint64_t)>;
struct UpdateInfo {
    std::string version;
    int version_code = 0;
    std::uint64_t linux_size = 0;
    std::string linux_sha256;
    std::string signed_manifest;
    std::string release_url;
};
enum class UpdateCheck { available, current, failed };
// All network/file operations are synchronous. Run them away from the GTK thread.
UpdateCheck check_for_update(UpdateInfo&, std::string& error, CancelCheck cancelled = {});
bool appimage_update_supported(std::string& error);
bool download_appimage_update(const UpdateInfo&, std::string& staging_path, std::string& error,
    CancelCheck cancelled = {}, ProgressCallback progress = {});
bool install_staged_appimage_update(const UpdateInfo&, const std::string& staging_path,
    std::string& backup_path, std::string& error, CancelCheck cancelled = {}, ProgressCallback progress = {});
bool install_appimage_update(const UpdateInfo&, std::string& backup_path, std::string& error,
    CancelCheck cancelled = {}, ProgressCallback progress = {});
void discard_staged_appimage_update(const std::string& staging_path);

// Shared validation helpers are public for offline regression tests. The network
// and installation entry points always use the compiled release public key.
namespace update_detail {
struct Manifest {
    UpdateInfo info;
    int protocol_min = 0, protocol_max = 0;
    std::uint64_t android_size = 0;
    std::string android_sha256;
};
bool parse_signed_manifest(const std::string& document, const std::string& public_key_pem,
    Manifest&, std::string& error);
int compare_versions(const std::string& left, const std::string& right);
bool compatible_manifest(const Manifest&, std::string& error);
bool allowed_download_url(const std::string& url);
std::string pinned_public_key();
#ifdef METUXA_UPDATE_TESTING
bool install_for_test(const std::string& document, const std::string& key,
    const std::string& staging_path, const std::string& target,
    std::string& backup_path, std::string& error, CancelCheck cancelled = {});
#endif
}
} // namespace metuxa::control
