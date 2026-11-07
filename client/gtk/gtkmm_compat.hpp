#pragma once
// GTK/GLib development snapshots promoted three C types to G_DECLARE_FINAL_TYPE
// before the released C++ bindings caught up (GTK 4.25 + gtkmm 4.22; GLib 2.90
// + glibmm 2.88). Their wrapper-only BaseClassType forward declarations then
// collide with anonymous C class typedefs. Isolate those unused class aliases;
// instance types, objects, function signatures, and installed headers are NOT
// changed. Stable SDKs take the normal include path. Remove once bindings catch
// up. This application does not subclass any of the three affected types.
#include <gtk/gtk.h>
#include <glibmmconfig.h>
#include <gtkmmconfig.h>
#if GLIB_CHECK_VERSION(2, 90, 0) && (GLIBMM_MAJOR_VERSION == 2 && GLIBMM_MINOR_VERSION < 90)
#define GDBusActionGroupClass MetuxaCompatDBusActionGroupClass
#include <giomm/dbusactiongroup.h>
#undef GDBusActionGroupClass
#define GEmblemClass MetuxaCompatEmblemClass
#include <giomm/emblem.h>
#undef GEmblemClass
#endif
#if GTK_CHECK_VERSION(4, 25, 0) && (GTKMM_MAJOR_VERSION == 4 && GTKMM_MINOR_VERSION < 24)
#define GdkCursorClass MetuxaCompatCursorClass
#include <gdkmm/cursor.h>
#undef GdkCursorClass
#endif
#include <gtkmm.h>
