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
assert "renderSurfaceDestroyed() { surfaceReady = false; stopStream(); }" in panel
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

# Display and input share the Android View transform, never a second pointer rotation.
assert "setMonitorRotation" not in panel and "settings.rotation" not in panel
assert "PanelWindow.followStream(this, width, height)" in panel
assert "requestOrientation(selected == 1)" in panel
for name in (".MainActivity", ".StreamActivity", ".MonitorActivity"):
    layout = activities[name].find("layout")
    assert layout is not None and layout.get(a + "defaultWidth") == "1280dp" and layout.get(a + "defaultHeight") == "720dp"
assert activities[".StreamActivity"].get(a + "taskAffinity") == "dev.metuxa.quest.stream"
assert "MonitorGeometry.fit(" in panel
assert "viewportParams.topMargin" not in panel and "viewportParams.bottomMargin" not in panel
assert "setOnHoverListener((v, e) -> capturePointer(e))" in capture
assert "setOnHoverListener(null)" in capture
assert "!isChangingConfigurations()" in panel
assert "pairingDialog == dialog" in main and "attempt[0].confirm()" in main
assert '"metuxa v${versionName}"' in (root / "app/build.gradle").read_text()

renderer = (src / "RemoteSurfaceView.java").read_text()
assert "extends TextureView" in renderer and "return false;" in renderer
assert "renderLease.close()" in (src / "StreamSession.java").read_text()

# Conscrypt close() may write close_notify; Activity stop must only schedule it.
stream = (src / "StreamSession.java").read_text()
stop = stream.split("public synchronized void stop()", 1)[1].split("public void sendPointerMove", 1)[0]
assert "closing.request()" in stop
assert "closeSignalingSocket()" not in stop and "closing.await()" not in stop and "socket.close()" not in stop
assert "new SocketShutdown(socket," in stream and "if(!running) return;" in stream

# Initial bounds come from a pinned metadata-only exchange, never a discovery hint.
probe = (src / "StreamMetadataProbe.java").read_text()
assert "SecureTransport::pinnedSocket" in probe and '"AUTH " + token' in probe
assert '"READY' not in probe and "RtpReceiver" not in probe and "VideoDecoder" not in probe
assert "close.await()" in probe and probe.index("close.await()") < probe.index("listener.complete(result)")
assert "!coordinator.matches(c.generation)" in main and "metadataProbe != attempt" in main
assert "c.primaryLaunched || c.connected" in main and "cancelMetadataProbe(false)" in main
