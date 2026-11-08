#!/usr/bin/env python3
"""Architectural invariants complement the executable Android-free tests."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
src = root / "app/src/main/java/com/metashare/client"
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
a = "{http://schemas.android.com/apk/res/android}"
app = manifest.find("application")
assert app.get(a + "allowBackup") == "false"
assert app.get(a + "usesCleartextTraffic") == "false"
assert app.get(a + "dataExtractionRules") == "@xml/data_extraction_rules"
activities = {e.get(a + "name"): e for e in app.findall("activity")}
assert activities[".MainActivity"].get(a + "exported") == "true"
for name in (".StreamActivity", ".MonitorActivity"):
    assert activities[name].get(a + "exported") == "false"
assert "applicationId 'dev.metuxa.quest'" in (root / "app/build.gradle").read_text()
main = (src / "MainActivity.java").read_text()
assert "new StreamSession" not in main
assert "!settings.autoHost().isEmpty()" in main
assert "getBooleanExtra(\"manage\", false)" in main
panel = (src / "BaseStreamActivity.java").read_text()
assert "primary() && connection.audio" in panel
assert "coordinator.matches(generation)" in panel
assert "if (!registered)" in panel
assert "monitorIndex >= Math.min(connection.desiredMonitors, connection.availableMonitors)" in panel
assert "if (!enabled) input.lostFocus()" in panel
assert "epoch == streamEpoch" in panel
assert "onWindowFocusChanged" in panel and "input.lostFocus()" in panel
assert "surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopStream(); }" in panel
capture = (src / "RemoteInputController.java").read_text()
assert "PointerPolicy.permits" in capture and "return true;" in capture
assert "ACTION_CANCEL" in capture and "ACTION_HOVER_EXIT" in capture
assert "TYPE_TEXT_FLAG_NO_SUGGESTIONS" in capture and "IME_FLAG_NO_PERSONALIZED_LEARNING" in capture
assert "TextWatcher" not in capture
assert "lifecycle.allowsInput() && activity.hasWindowFocus()" in capture
assert "event.getEventTime() <= rearmAtMs" in capture
assert "input.setResumed(false)" in panel
assert "input.setWindowFocused(focused)" in panel
ledger = (src / "SessionInputForwarder.java").read_text()
assert "state.clearDisconnected()" in ledger
assert "session.releaseAllInput()" in ledger
meta = (src / "InputDiagnostics.java").read_text()
assert "FileOutputStream" not in meta and "Log." not in meta and "getX(" not in meta
assert "AES/GCM/NoPadding" in (src / "CredentialVault.java").read_text()
assert "updateAAD" in (src / "CredentialVault.java").read_text()
assert "epoch == keyboardEpoch && options.keyboard && acceptsInput()" in capture
assert "event.getRepeatCount() > 0 && !heldHardware.contains(code)" in capture
assert "listener.onInputPolicy(options)" in capture
assert "settings.inputOptions()" in capture
assert "settings.excludedDevices()" in capture
assert "state.configure(next)" in ledger and "cancelKeyboard()" in ledger
assert "!ready() || !options.keyboard" in ledger
settings = (src / "AppSettings.java").read_text()
for key in ("MOVE", "CLICK", "SCROLL", "KEYBOARD"):
    assert "putBoolean(InputOptions." + key in settings
assert '"excluded_devices"' in settings
assert "refreshInputControls()" in main and "options.presetLabel()" in main
assert "chooseDevices(true)" in main and "chooseDevices(false)" in main
assert "All identifiable physical pointer devices except selected" in main
assert "pointerOwnership.gate" in capture
assert capture.index("decision == PointerOwnership.IGNORE") < capture.index("action == MotionEvent.ACTION_CANCEL")
assert "registerInputDeviceListener(deviceListener" in capture
assert "unregisterInputDeviceListener(deviceListener)" in capture
assert "pointerOwnership.removed(deviceId)" in capture
assert "input.releaseHeldKey(event)" in panel
assert panel.index("input.releaseHeldKey(event)") < panel.index("focused instanceof android.widget.Button")
assert "return super.dispatchKeyEvent(event);" in panel
print("metuxa architecture: static invariants passed")

# Keep the native palette and platform/dialog themes aligned without a device.
import runpy
runpy.run_path(str(root / "tests/theme-checks.py"))
