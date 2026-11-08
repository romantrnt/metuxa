package com.metashare.client;

/** Serial operation ownership and callback invalidation, independent of Android. */
final class UpdateOperationState {
    private int generation;
    private boolean busy, closed, cancelled;
    synchronized int begin() {
        if(closed)return -1;
        if(busy)return -2;
        busy=true; cancelled=false; return ++generation;
    }
    synchronized boolean current(int token) { return !closed && token==generation; }
    synchronized boolean finish(int token) {
        if(!current(token))return false;
        busy=false; return true;
    }
    synchronized boolean cancelled() { return cancelled || closed; }
    synchronized void cancel() { cancelled=true; }
    synchronized void close() { closed=true; cancelled=true; busy=false; ++generation; }
}
