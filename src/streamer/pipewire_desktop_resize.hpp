#pragma once
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include "display_control.hpp"

namespace metashare {
// Called with the PipeWire thread loop locked. Exact dimensions, not a range:
// Mutter uses this negotiation to set the owned virtual monitor's real mode.
inline bool request_desktop_size(pw_stream* stream, int width, int height, int fps) {
    if (!stream || !display::valid_size(width, height)) return false;
    std::uint8_t buffer[1024];
    auto builder = SPA_POD_BUILDER_INIT(buffer, sizeof(buffer));
    auto rectangle = SPA_RECTANGLE(static_cast<std::uint32_t>(width), static_cast<std::uint32_t>(height));
    auto rate = SPA_FRACTION(static_cast<std::uint32_t>(fps), 1);
    auto minimum = SPA_FRACTION(0, 1);
    auto maximum = SPA_FRACTION(240, 1);
    const spa_pod* params[] = {static_cast<const spa_pod*>(spa_pod_builder_add_object(
        &builder, SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat,
        SPA_FORMAT_mediaType, SPA_POD_Id(SPA_MEDIA_TYPE_video),
        SPA_FORMAT_mediaSubtype, SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
        SPA_FORMAT_VIDEO_format,
        SPA_POD_CHOICE_ENUM_Id(5, SPA_VIDEO_FORMAT_BGRA, SPA_VIDEO_FORMAT_BGRA,
                              SPA_VIDEO_FORMAT_RGBA, SPA_VIDEO_FORMAT_BGRx, SPA_VIDEO_FORMAT_RGBx),
        SPA_FORMAT_VIDEO_size, SPA_POD_Rectangle(&rectangle),
        SPA_FORMAT_VIDEO_framerate, SPA_POD_CHOICE_RANGE_Fraction(&rate, &minimum, &maximum)))};
    return pw_stream_update_params(stream, params, 1) >= 0;
}
}  // namespace metashare
