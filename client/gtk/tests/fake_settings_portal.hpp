#pragma once
#include <gio/gio.h>
#include <cstring>
#include <stdexcept>

// Register only on a test-owned bus, never on the user's session bus.
class FakeSettingsPortal {
public:
    guint32 scheme = 1;
    bool legacy = false, hold_read = false;
    unsigned reads = 0, legacy_reads = 0;
    GDBusMethodInvocation* pending = nullptr;

    FakeSettingsPortal() {
        GError* error = nullptr;
        connection_ = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, &error);
        if (!connection_) fail(error);
        const char* xml = R"xml(<node><interface name="org.freedesktop.portal.Settings">
          <method name="ReadOne"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
          <method name="Read"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
          <signal name="SettingChanged"><arg type="s"/><arg type="s"/><arg type="v"/></signal>
        </interface></node>)xml";
        info_ = g_dbus_node_info_new_for_xml(xml, &error);
        if (!info_) fail(error);
        static const GDBusInterfaceVTable table = {
            .method_call = +[](GDBusConnection*, const gchar*, const gchar*,
                              const gchar*, const gchar* method,
                              GVariant* parameters, GDBusMethodInvocation* call,
                              gpointer data) {
                auto& self = *static_cast<FakeSettingsPortal*>(data);
                const char *space, *key;
                g_variant_get(parameters, "(&s&s)", &space, &key);
                if (std::strcmp(space, "org.freedesktop.appearance") ||
                    std::strcmp(key, "color-scheme")) {
                    g_dbus_method_invocation_return_dbus_error(call,
                        "org.freedesktop.portal.Error.NotFound", "Unknown setting");
                    return;
                }
                ++self.reads;
                if (std::strcmp(method, "ReadOne") == 0 && self.legacy) {
                    g_dbus_method_invocation_return_dbus_error(call,
                        "org.freedesktop.DBus.Error.UnknownMethod", "Use Read");
                    return;
                }
                const bool old = std::strcmp(method, "Read") == 0;
                if (old) ++self.legacy_reads;
                if (self.hold_read) {
                    self.pending = G_DBUS_METHOD_INVOCATION(g_object_ref(call));
                    return;
                }
                self.reply(call, self.scheme, old);
            },
        };
        registration_ = g_dbus_connection_register_object(connection_,
            "/org/freedesktop/portal/desktop", info_->interfaces[0], &table,
            this, nullptr, &error);
        if (!registration_) fail(error);
        acquire();
    }
    ~FakeSettingsPortal() {
        if (pending) finish_read(scheme);
        release();
        g_dbus_connection_unregister_object(connection_, registration_);
        g_dbus_node_info_unref(info_);
        g_object_unref(connection_);
    }
    void acquire() { name_call("RequestName", g_variant_new("(su)", "org.freedesktop.portal.Desktop", 0u)); }
    void release() { name_call("ReleaseName", g_variant_new("(s)", "org.freedesktop.portal.Desktop")); }
    void change(guint32 value, const char* space = "org.freedesktop.appearance",
                const char* key = "color-scheme") {
        scheme = value;
        g_dbus_connection_emit_signal(connection_, nullptr,
            "/org/freedesktop/portal/desktop", "org.freedesktop.portal.Settings",
            "SettingChanged", g_variant_new("(ssv)", space, key,
                                            g_variant_new_uint32(value)), nullptr);
        g_dbus_connection_flush_sync(connection_, nullptr, nullptr);
    }
    void finish_read(guint32 value) {
        reply(pending, value, legacy);
        g_clear_object(&pending);
    }
private:
    GDBusConnection* connection_ = nullptr;
    GDBusNodeInfo* info_ = nullptr;
    guint registration_ = 0;
    static void fail(GError* error) {
        std::string message = error ? error->message : "Fake portal setup failed";
        g_clear_error(&error);
        throw std::runtime_error(message);
    }
    static void reply(GDBusMethodInvocation* call, guint32 value, bool legacy) {
        auto* content = g_variant_new_uint32(value);
        if (legacy) content = g_variant_new_variant(content);
        g_dbus_method_invocation_return_value(call, g_variant_new("(v)", content));
    }
    void name_call(const char* method, GVariant* parameters) {
        GError* error = nullptr;
        auto* result = g_dbus_connection_call_sync(connection_,
            "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus", method, parameters, G_VARIANT_TYPE("(u)"),
            G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
        if (!result) fail(error);
        g_variant_unref(result);
    }
};
