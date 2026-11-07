package com.metashare.client;

/** Secondary renderer accepts only the in-process selected session generation. */
public final class MonitorActivity extends BaseStreamActivity {
    @Override boolean primary() { return false; }
}
