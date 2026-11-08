#pragma once

#include <gio/gio.h>
#include <functional>
#include <memory>
#include <optional>

namespace metuxa::control {
// The Settings portal uses 0 for no preference, 1 for dark and 2 for light.
// ReadOne returns (v); older Read implementations may wrap the value twice.
std::optional<bool> portal_color_scheme(GVariant* value);
std::optional<bool> gnome_color_scheme(const char* value);

// Only reads appearance settings. Callbacks run on the creating main context.
// No GTK property is used as input, so application overrides cannot become the
// system preference when the user switches back to System.
class SystemAppearance {
public:
    explicit SystemAppearance(std::function<void()> changed);
    ~SystemAppearance();
    SystemAppearance(const SystemAppearance&) = delete;
    SystemAppearance& operator=(const SystemAppearance&) = delete;
    std::optional<bool> dark() const;

private:
    struct State;
    std::shared_ptr<State> state_;
};
} // namespace metuxa::control
