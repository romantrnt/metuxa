package android.os;
public final class HandlerThread extends Thread {
    private final Looper looper=new Looper();
    public HandlerThread(String name) {super(name);setDaemon(true);}
    public Looper getLooper() {return looper;}
    @Override public void run() {looper.loop();}
    public boolean quitSafely() {looper.quit=true;interrupt();return true;}
}
