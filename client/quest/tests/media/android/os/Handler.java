package android.os;
public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) {this.looper=looper;}
    public boolean post(Runnable runnable) {return postDelayed(runnable,0);}
    public boolean postDelayed(Runnable runnable,long delay) {if(looper.quit)return false;looper.tasks.add(new Looper.Task(runnable,delay));return true;}
    public void removeCallbacks(Runnable runnable) {looper.tasks.removeIf(task->task.runnable==runnable);}
    public void removeCallbacksAndMessages(Object token) {looper.tasks.clear();}
}
