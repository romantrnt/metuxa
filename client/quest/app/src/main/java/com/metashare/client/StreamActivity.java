package com.metashare.client;

/** Primary renderer and sole owner of audio and monitor creation. */
public final class StreamActivity extends BaseStreamActivity {
    @Override boolean primary() { return true; }
}
