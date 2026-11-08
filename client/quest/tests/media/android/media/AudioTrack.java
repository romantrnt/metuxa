package android.media;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
public final class AudioTrack {
    public static final int MODE_STREAM=1,PERFORMANCE_MODE_LOW_LATENCY=1,WRITE_NON_BLOCKING=1;
    public static volatile AudioTrack latest;
    public static volatile CountDownLatch writeEntered,writeContinue;
    private final Thread owner=Thread.currentThread();
    public volatile boolean released;
    public static int getMinBufferSize(int rate,int mask,int encoding){return 1024;}
    private void check(){if(Thread.currentThread()!=owner||released)throw new AssertionError("AudioTrack ownership violation");}
    public void play(){check();}
    public void stop(){check();}
    public void release(){check();released=true;}
    public int write(ByteBuffer data,int size,int mode){check();MediaCodec.block(writeEntered,writeContinue);check();return size;}
    public int getUnderrunCount(){check();return 0;}
    public static final class Builder{public Builder setAudioAttributes(AudioAttributes value){return this;}public Builder setAudioFormat(AudioFormat value){return this;}public Builder setBufferSizeInBytes(int value){return this;}public Builder setTransferMode(int value){return this;}public Builder setPerformanceMode(int value){return this;}public AudioTrack build(){latest=new AudioTrack();return latest;}}
}
