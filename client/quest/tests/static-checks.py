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
assert "if (!acceptsInput()) return true;" in capture
assert "input.setResumed(false)" in panel
assert "input.setWindowFocused(focused)" in panel
ledger = (src / "SessionInputForwarder.java").read_text()
assert "state.clearDisconnected()" in ledger
assert "session.releaseAllInput()" in ledger
meta = (src / "InputDiagnostics.java").read_text()
assert "FileOutputStream" not in meta and "Log." not in meta and "getX(" not in meta
assert "AES/GCM/NoPadding" in (src / "CredentialVault.java").read_text()
assert "updateAAD" in (src / "CredentialVault.java").read_text()
print("metuxa architecture: static invariants passed")
