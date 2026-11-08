#!/usr/bin/env python3
"""Offline palette/contrast contracts, not a substitute for headset UI testing."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / "app/src/main/res"
src = root / "app/src/main/java/com/metashare/client"
a = "{http://schemas.android.com/apk/res/android}"
colors = {item.attrib["name"]: item.text for item in ET.parse(res / "values/colors.xml").getroot()}
ui = (src / "QuestUi.java").read_text()
checks = 0


def check(condition, message):
    global checks
    assert condition, message
    checks += 1


def rgb(value):
    value = value.lstrip("#")
    check(len(value) == 6, "Contrast colors must be opaque")
    return tuple(int(value[i:i + 2], 16) / 255 for i in (0, 2, 4))


def luminance(value):
    r, g, b = (c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in rgb(value))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(foreground, background):
    high, low = sorted((luminance(colors[foreground]), luminance(colors[background])), reverse=True)
    return (high + 0.05) / (low + 0.05)


# Java-drawn surfaces and Android-themed controls share the same source palette.
for key in ("BACKGROUND", "PANEL", "FIELD", "TEXT", "MUTED", "ACCENT", "INK", "BORDER", "OUTLINE", "HOVER", "DISABLED", "ERROR"):
    color = colors["quest_" + key.lower()]
    value = re.search(r"\b" + key + r" = 0xff([0-9a-f]{6})", ui)
    check(value is not None and value.group(1).lower() == color[1:].lower(), "Palette mismatch: " + key)
    if key != "ERROR":
        r, g, b = rgb(color)
        check(r == g == b, "Unexpected colored tint: " + key)

# All enabled body text reaches WCAG AA, including hovered secondary buttons.
minimum = 21.0
for surface in ("background", "panel", "field", "hover"):
    for foreground in ("text", "muted", "error"):
        ratio = contrast("quest_" + foreground, "quest_" + surface)
        minimum = min(minimum, ratio)
        check(ratio >= 4.5, f"Insufficient {foreground}/{surface} text contrast: {ratio:.2f}")
check(contrast("quest_ink", "quest_accent") >= 4.5, "Primary/selected button text contrast")
check(contrast("quest_ink", "quest_text") >= 4.5, "Hovered primary/selected button text contrast")
check(contrast("quest_outline", "quest_field") >= 3.0, "Input field/secondary button edge contrast")
check(contrast("quest_accent", "quest_hover") >= 3.0, "Keyboard-focus outline contrast")

styles = {item.get("name"): item for item in ET.parse(res / "values/styles.xml").getroot()}
expected = {
    "colorAccent": "@color/quest_accent", "colorControlActivated": "@color/quest_accent",
    "colorControlNormal": "@color/quest_muted", "colorControlHighlight": "@color/quest_ripple",
    "colorBackgroundFloating": "@color/quest_panel", "textColorPrimary": "@color/quest_text",
    "textColorSecondary": "@color/quest_muted", "textColorHighlight": "@color/quest_selection",
    "windowLightStatusBar": "false", "windowLightNavigationBar": "false",
    "forceDarkAllowed": "false",
}
for theme in ("MetuxaTheme", "MetuxaDialogTheme"):
    items = {item.get("name"): item.text for item in styles[theme]}
    for attr, value in expected.items():
        check(items.get("android:" + attr) == value, f"Unpinned {theme}/{attr}")
check(any(item.get("name") == "android:alertDialogTheme" and item.text == "@style/MetuxaDialogTheme"
          for item in styles["MetuxaTheme"]), "Dialogs must use the graphite theme")
for widget in ("MetuxaCheckBox", "MetuxaRadioButton"):
    items = {item.get("name"): item.text for item in styles[widget]}
    check(items.get("android:buttonTint") == "@color/quest_control_tint", "Toggle tint missing")
    check(items.get("android:minHeight") == "48dp", "Small toggle target")

for name in ("quest_spinner_value", "quest_spinner_option"):
    layout = ET.parse(res / f"layout/{name}.xml").getroot()
    check(layout.get(a + "textColor") == "@color/quest_text", "Inherited spinner text color")
    check(layout.get(a + "minHeight") == "48dp", "Small spinner target")
for name in ("quest_control_tint", "quest_text_states"):
    selector = ET.parse(res / f"color/{name}.xml").getroot()
    check(selector[0].get(a + "state_enabled") == "false", "Disabled tint state missing")
check("state_focused" in ui and "state_hovered" in ui and "state_selected" in ui,
      "Button interaction states must remain visible")
check("new RippleDrawable" in ui, "Pressed feedback missing")
check("class AdaptiveActions" in ui and "child.getMeasuredWidth()" in ui
      and "params.resolveLayoutDirection(getLayoutDirection())" in ui,
      "Adaptive actions must stack to fit and resolve their updated spacing")
main = (src / "MainActivity.java").read_text()
check("windowsLabel.setLabelFor(monitorCount.getId())" in main, "Spinner label missing")
check("title.setLabelFor(edit.getId())" in main, "Editable field labels missing")
check("setAccessibilityHeading(true)" in ui, "Section heading semantics missing")
check("setBackgroundTintList(null)" in ui and "edit.setBackgroundTintList(null)" in main,
      "Platform background tint may override the custom control palette")
print(f"metuxa graphite theme: {checks} checks passed; minimum enabled text contrast {minimum:.2f}:1")

# Light mode uses the same controls with a separate neutral resource palette.
colors = {item.attrib["name"]: item.text for item in ET.parse(res / "values-notnight/colors.xml").getroot()}
light_minimum = 21.0
for surface in ("background", "panel", "field", "hover"):
    for foreground in ("text", "muted", "error"):
        ratio = contrast("quest_" + foreground, "quest_" + surface)
        light_minimum = min(light_minimum, ratio)
        check(ratio >= 4.5, f"Light {foreground}/{surface} contrast: {ratio:.2f}")
check(contrast("quest_ink", "quest_accent") >= 4.5, "Light selected text contrast")
check(contrast("quest_outline", "quest_field") >= 3, "Light field edge contrast")
check("themedContext(base)" in main, "Launcher must apply saved theme before inflation")
print(f"metuxa light theme: minimum enabled text contrast {light_minimum:.2f}:1")
