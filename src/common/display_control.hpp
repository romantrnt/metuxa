// Optional v5 desktop-orientation capability. No image rotation is involved.
#pragma once
#include <algorithm>
#include <cstdint>
#include <string_view>

namespace metashare::display {
enum class Source { Unknown, Physical, Window, Virtual, Test };
inline const char* name(Source source) {
    switch (source) {
    case Source::Physical: return "physical";
    case Source::Window: return "window";
    case Source::Virtual: return "virtual";
    case Source::Test: return "test";
    default: return "unknown";
    }
}
inline Source portal_source(std::uint32_t type) {
    return type == 1 ? Source::Physical : type == 2 ? Source::Window :
           type == 4 ? Source::Virtual : Source::Unknown;
}
// A requested virtual source alone is not proof of the granted source type.
inline bool owns_virtual(std::uint32_t requested, std::uint32_t granted) {
    return requested == 4 && granted == 4;
}
inline bool valid_size(int width, int height) {
    return width >= 240 && height >= 240 && width <= 7680 && height <= 7680 &&
           width % 2 == 0 && height % 2 == 0 &&
           std::int64_t(width) * height <= 7680LL * 4320;
}
inline bool dimensions(int width, int height, bool portrait, int& out_width, int& out_height) {
    if (!valid_size(width, height)) return false;
    out_width = portrait ? std::min(width, height) : std::max(width, height);
    out_height = portrait ? std::max(width, height) : std::min(width, height);
    return true;
}
}  // namespace metashare::display
