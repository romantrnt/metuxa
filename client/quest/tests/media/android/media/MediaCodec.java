package android.media;
import android.os.Handler;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
public final class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME=1;
    public static final List<MediaCodec> instances=new CopyOnWriteArrayList<>();
    public static volatile boolean failConfigure;
    public static volatile CountDownLatch configureEntered,configureContinue;
    public final List<String> calls=new CopyOnWriteArrayList<>();
    public final Thread owner=Thread.currentThread();
    public Callback callback; public Handler handler;
    public volatile CountDownLatch resetEntered,resetContinue;
    public volatile boolean released;
    public volatile Surface outputSurface;
    private MediaCodec(){calls.add("create");}
    public static MediaCodec createDecoderByType(String mime){MediaCodec codec=new MediaCodec();instances.add(codec);return codec;}
    private void call(String name){if(Thread.currentThread()!=owner)throw new AssertionError(name+" on wrong thread");if(released)throw new AssertionError(name+" after release");calls.add(name);}
    public void setCallback(Callback callback,Handler handler){call("callback");this.callback=callback;this.handler=handler;}
    public MediaCodecInfo getCodecInfo(){call("info");return new MediaCodecInfo();}
    public void configure(MediaFormat format,Surface surface,Object crypto,int flags){call("configure");outputSurface=surface;block(configureEntered,configureContinue);if(failConfigure){failConfigure=false;throw new IllegalStateException("injected configure failure");}}
    public void start(){call("start");}
    public void reset(){call("reset");block(resetEntered,resetContinue);}
    public void stop(){call("stop");}
    public void release(){call("release");released=true;}
    public void setOutputSurface(Surface surface){call("surface");outputSurface=surface;}
    public ByteBuffer getInputBuffer(int index){call("input");return ByteBuffer.allocate(65536);}
    public ByteBuffer getOutputBuffer(int index){call("output");return ByteBuffer.allocate(65536);}
    public void queueInputBuffer(int index,int offset,int size,long pts,int flags){call("queue");}
    public void releaseOutputBuffer(int index,boolean render){call("render");}
    public void releaseOutputBuffer(int index,long renderNs){call("render");}
    public static void block(CountDownLatch entered,CountDownLatch resume){if(entered!=null)entered.countDown();if(resume!=null){boolean interrupted=false;for(;;)try{resume.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}}
    public static final class BufferInfo {public int offset,size;public long presentationTimeUs;}
    public static final class CodecException extends RuntimeException {private static final long serialVersionUID=1L;public int getErrorCode(){return 1;}public boolean isRecoverable(){return true;}public boolean isTransient(){return false;}}
    public abstract static class Callback {
        public abstract void onInputBufferAvailable(MediaCodec codec,int index);
        public abstract void onOutputBufferAvailable(MediaCodec codec,int index,BufferInfo info);
        public abstract void onOutputFormatChanged(MediaCodec codec,MediaFormat format);
        public abstract void onError(MediaCodec codec,CodecException error);
    }
}
