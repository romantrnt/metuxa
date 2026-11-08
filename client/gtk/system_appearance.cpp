#include "system_appearance.hpp"

#include <cstring>
#include <utility>

namespace metuxa::control {
std::optional<bool> portal_color_scheme(GVariant* value) {
    if (!value) return {};
    GVariant* current = g_variant_ref(value);
    for (unsigned depth = 0; depth < 8; ++depth) {
        GVariant* child = nullptr;
        if (g_variant_is_of_type(current, G_VARIANT_TYPE_VARIANT)) {
            child = g_variant_get_variant(current);
        } else if (g_variant_is_of_type(current, G_VARIANT_TYPE_TUPLE) &&
                   g_variant_n_children(current) == 1) {
            child = g_variant_get_child_value(current, 0);
        } else {
            break;
        }
        g_variant_unref(current);
        current = child;
    }
    std::optional<bool> result;
    if (g_variant_is_of_type(current, G_VARIANT_TYPE_UINT32)) {
        const auto scheme = g_variant_get_uint32(current);
        if (scheme == 1 || scheme == 2) result = scheme == 1;
    }
    g_variant_unref(current);
    return result;
}

std::optional<bool> gnome_color_scheme(const char* value) {
    if (g_strcmp0(value, "prefer-dark") == 0) return true;
    if (g_strcmp0(value, "prefer-light") == 0) return false;
    return {};
}

struct SystemAppearance::State : std::enable_shared_from_this<State> {
    std::function<void()> changed;
    GSettings* gnome = nullptr;
    GDBusProxy* portal = nullptr;
    GCancellable* cancel = g_cancellable_new();
    std::optional<bool> portal_dark, gnome_dark;
    unsigned generation = 0;
    bool stopped = false;

    struct Read {
        std::shared_ptr<State> state;
        unsigned generation;
        bool legacy;
    };

    ~State() { g_object_unref(cancel); }

    std::optional<bool> dark() const {
        return portal_dark.has_value() ? portal_dark : gnome_dark;
    }

    void set(std::optional<bool>& source, std::optional<bool> value) {
        const auto old = dark();
        source = value;
        if (old != dark() && changed) changed();
    }

    void read_gnome() {
        gchar* value = g_settings_get_string(gnome, "color-scheme");
        set(gnome_dark, gnome_color_scheme(value));
        g_free(value);
    }

    void start() {
        // AppImages and non-GNOME desktops may have no GNOME schema or dconf
        // module. The portal remains the primary, cross-desktop source.
        auto* source = g_settings_schema_source_get_default();
        auto* schema = source ? g_settings_schema_source_lookup(
                                    source, "org.gnome.desktop.interface", true)
                              : nullptr;
        if (schema) {
            if (g_settings_schema_has_key(schema, "color-scheme")) {
                gnome = g_settings_new_full(schema, nullptr, nullptr);
                read_gnome();
                g_signal_connect(gnome, "changed::color-scheme",
                    G_CALLBACK(+[](GSettings*, gchar*, gpointer data) {
                        static_cast<State*>(data)->read_gnome();
                    }), this);
            }
            g_settings_schema_unref(schema);
        }
        g_dbus_proxy_new_for_bus(G_BUS_TYPE_SESSION,
            G_DBUS_PROXY_FLAGS_DO_NOT_LOAD_PROPERTIES, nullptr,
            "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Settings", cancel,
            +[](GObject*, GAsyncResult* result, gpointer data) {
                std::unique_ptr<std::shared_ptr<State>> holder(
                    static_cast<std::shared_ptr<State>*>(data));
                auto state = *holder;
                GError* error = nullptr;
                auto* proxy = g_dbus_proxy_new_for_bus_finish(result, &error);
                g_clear_error(&error);
                if (state->stopped || !proxy) {
                    if (proxy) g_object_unref(proxy);
                    return;
                }
                state->portal = proxy;
                g_signal_connect(proxy, "g-signal", G_CALLBACK(signal), state.get());
                g_signal_connect(proxy, "notify::g-name-owner",
                    G_CALLBACK(+[](GObject*, GParamSpec*, gpointer data) {
                        static_cast<State*>(data)->owner_changed();
                    }), state.get());
                state->owner_changed();
            }, new std::shared_ptr<State>(shared_from_this()));
    }

    static void signal(GDBusProxy*, gchar*, gchar* name, GVariant* parameters,
                       gpointer data) {
        if (std::strcmp(name, "SettingChanged") != 0 ||
            !g_variant_is_of_type(parameters, G_VARIANT_TYPE("(ssv)"))) return;
        const char *space, *key;
        GVariant* value = nullptr;
        g_variant_get(parameters, "(&s&sv)", &space, &key, &value);
        if (std::strcmp(space, "org.freedesktop.appearance") == 0 &&
            std::strcmp(key, "color-scheme") == 0) {
            auto* state = static_cast<State*>(data);
            ++state->generation; // A late initial Read must not undo this signal.
            state->set(state->portal_dark, portal_color_scheme(value));
        }
        g_variant_unref(value);
    }

    void owner_changed() {
        ++generation;
        set(portal_dark, {});
        gchar* owner = g_dbus_proxy_get_name_owner(portal);
        if (owner) read(false);
        g_free(owner);
    }

    void read(bool legacy) {
        g_dbus_proxy_call(portal, legacy ? "Read" : "ReadOne",
            g_variant_new("(ss)", "org.freedesktop.appearance", "color-scheme"),
            G_DBUS_CALL_FLAGS_NONE, 2000, cancel,
            +[](GObject* object, GAsyncResult* result, gpointer data) {
                std::unique_ptr<Read> request(static_cast<Read*>(data));
                auto state = request->state;
                GError* error = nullptr;
                GVariant* reply = g_dbus_proxy_call_finish(
                    G_DBUS_PROXY(object), result, &error);
                if (!state->stopped && request->generation == state->generation) {
                    if (!request->legacy &&
                        g_error_matches(error, G_DBUS_ERROR,
                                        G_DBUS_ERROR_UNKNOWN_METHOD)) {
                        state->read(true);
                    } else {
                        state->set(state->portal_dark, portal_color_scheme(reply));
                    }
                }
                if (reply) g_variant_unref(reply);
                g_clear_error(&error);
            }, new Read{shared_from_this(), generation, legacy});
    }

    void stop() {
        stopped = true;
        changed = {};
        g_cancellable_cancel(cancel);
        if (gnome) g_signal_handlers_disconnect_by_data(gnome, this);
        if (portal) g_signal_handlers_disconnect_by_data(portal, this);
        g_clear_object(&gnome);
        g_clear_object(&portal);
    }
};

SystemAppearance::SystemAppearance(std::function<void()> changed)
    : state_(std::make_shared<State>()) {
    // Read the synchronous fallback before publishing callbacks to the caller.
    state_->start();
    state_->changed = std::move(changed);
}
SystemAppearance::~SystemAppearance() { state_->stop(); }
std::optional<bool> SystemAppearance::dark() const { return state_->dark(); }
} // namespace metuxa::control
