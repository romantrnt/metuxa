package com.metashare.client;

import android.media.*;
import android.os.Looper;
import android.view.Surface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic ownership/race tests against the production decoders, not hardware emulation. */
public final class MediaLifecycleTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);if(Looper.failure!=null)throw new AssertionError("Callback failed",Looper.failure);}
    private static void await(CountDownLatch latch)throws Exception{check(latch.await(3,TimeUnit.SECONDS),"Timed out waiting for test checkpoint");}
    private static void join(Thread thread)throws Exception{thread.join(3000);check(!thread.isAlive(),"Worker did not terminate");}
    private static MediaCodec latest(){return MediaCodec.instances.get(MediaCodec.instances.size()-1);}
    private static void idle(MediaCodec codec)throws Exception{CountDownLatch done=new CountDownLatch(1);check(codec.handler.post(done::countDown),"Callback handler accepts checkpoint");await(done);}
    private static VideoDecoder video(Surface surface,AtomicInteger notifications)throws Exception{
        VideoDecoder decoder=new VideoDecoder();decoder.init(surface,"h265",1920,1080,new VideoDecoder.Listener(){public void onFirstFrameRendered(){notifications.incrementAndGet();}public void onFrameResolutionChanged(int w,int h){notifications.incrementAndGet();}});return decoder;
    }
    private static void lateCallbacks(MediaCodec codec){
        codec.callback.onInputBufferAvailable(codec,0);
        codec.callback.onOutputBufferAvailable(codec,0,new MediaCodec.BufferInfo());
        codec.callback.onOutputFormatChanged(codec,MediaFormat.createVideoFormat("hevc",100,100));
        codec.callback.onError(codec,new MediaCodec.CodecException());
    }
    private static void releaseRecoveryRace()throws Exception{
        AtomicInteger notices=new AtomicInteger();VideoDecoder decoder=video(new Surface(),notices);MediaCodec codec=latest();
        codec.resetEntered=new CountDownLatch(1);codec.resetContinue=new CountDownLatch(1);
        codec.handler.post(()->codec.callback.onError(codec,new MediaCodec.CodecException()));await(codec.resetEntered);
        CountDownLatch closing=new CountDownLatch(1);Thread release=new Thread(()->{closing.countDown();decoder.release();},"release-test");release.start();await(closing);
        Thread duplicate=new Thread(decoder::release,"duplicate-release");duplicate.start();Thread.sleep(40);
        check(release.isAlive()&&!codec.released,"Teardown waits behind in-flight recovery");
        release.interrupt();codec.resetContinue.countDown();join(release);join(duplicate);
        check(release.isInterrupted(),"Cleanup preserves interrupt status");
        check(codec.calls.stream().filter("release"::equals).count()==1,"Codec released exactly once");
        check(codec.calls.get(codec.calls.size()-1).equals("release"),"No codec work after release");
        int callCount=codec.calls.size();lateCallbacks(codec);decoder.feed(new byte[]{1},0,true);decoder.setSurface(new Surface());
        check(codec.calls.size()==callCount&&notices.get()==0,"Late callback/input/surface work suppressed");
    }
    private static void deferredRecovery()throws Exception{
        Surface surface=new Surface();VideoDecoder decoder=video(surface,new AtomicInteger());MediaCodec codec=latest();surface.valid=false;
        codec.handler.post(()->codec.callback.onError(codec,new MediaCodec.CodecException()));idle(codec);decoder.release();surface.valid=true;Thread.sleep(240);
        check(!codec.calls.contains("reset"),"Delayed recovery cancelled on close");
    }
    private static void partialInitialization()throws Exception{
        MediaCodec.failConfigure=true;VideoDecoder decoder=new VideoDecoder();boolean failed=false;
        try{decoder.init(new Surface(),"h264",100,100,null);}catch(IllegalStateException expected){failed=true;}
        MediaCodec codec=latest();decoder.release();decoder.release();check(failed&&codec.released,"Partial initialization cleaned up once");
        VideoDecoder neverStarted=new VideoDecoder();neverStarted.release();neverStarted.release();
        boolean rejected=false;try{neverStarted.init(new Surface(),"h264",100,100,null);}catch(IllegalStateException expected){rejected=true;}
        check(rejected,"Initialization after release rejected");
    }
    private static void closeDuringInitialization()throws Exception{
        MediaCodec.configureEntered=new CountDownLatch(1);MediaCodec.configureContinue=new CountDownLatch(1);
        VideoDecoder decoder=new VideoDecoder();AtomicBoolean rejected=new AtomicBoolean();
        Thread initializing=new Thread(()->{try{decoder.init(new Surface(),"h265",100,100,null);}catch(Exception expected){rejected.set(true);}},"init-test");initializing.start();await(MediaCodec.configureEntered);
        Thread closing=new Thread(decoder::release,"close-test");closing.start();Thread.sleep(30);check(closing.isAlive(),"Close cannot race pending configure");
        MediaCodec.configureContinue.countDown();join(initializing);join(closing);check(rejected.get()&&latest().released,"Concurrent initialization fails closed and cleans up");
        MediaCodec.configureEntered=null;MediaCodec.configureContinue=null;
    }
    private static void surfaceDuringInitialization()throws Exception{
        MediaCodec.configureEntered=new CountDownLatch(1);MediaCodec.configureContinue=new CountDownLatch(1);
        VideoDecoder decoder=new VideoDecoder();Surface original=new Surface(),replacement=new Surface();
        java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread initializing=new Thread(()->{try{decoder.init(original,"h265",100,100,null);}catch(Exception error){failure.set(error);}},"surface-init");initializing.start();await(MediaCodec.configureEntered);
        decoder.setSurface(replacement);MediaCodec.configureContinue.countDown();join(initializing);MediaCodec codec=latest();idle(codec);
        check(failure.get()==null&&codec.outputSurface==replacement,"Surface replacement during configure is applied after initialization");decoder.release();
        MediaCodec.configureEntered=null;MediaCodec.configureContinue=null;
    }
    private static void callbackThreadClose()throws Exception{
        VideoDecoder decoder=new VideoDecoder();CountDownLatch closed=new CountDownLatch(1);
        decoder.init(new Surface(),"h264",100,100,new VideoDecoder.Listener(){public void onFirstFrameRendered(){decoder.release();closed.countDown();}public void onFrameResolutionChanged(int w,int h){}});
        MediaCodec codec=latest();codec.handler.post(()->codec.callback.onOutputBufferAvailable(codec,0,new MediaCodec.BufferInfo()));await(closed);
        check(codec.released,"Release on callback thread does not deadlock");decoder.release();
    }
    private static void audioOwnership()throws Exception{
        AudioDecoder decoder=new AudioDecoder();decoder.init(48000,2);MediaCodec codec=latest();AudioTrack track=AudioTrack.latest;
        codec.handler.post(()->codec.callback.onInputBufferAvailable(codec,0));idle(codec);decoder.feed(new byte[]{1,2,3},0,3,0);idle(codec);check(codec.calls.contains("queue"),"Audio feed is queued on codec owner thread");
        AudioTrack.writeEntered=new CountDownLatch(1);AudioTrack.writeContinue=new CountDownLatch(1);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.size=4;
        codec.handler.post(()->codec.callback.onOutputBufferAvailable(codec,0,info));await(AudioTrack.writeEntered);
        Thread closing=new Thread(decoder::release,"audio-close");closing.start();Thread.sleep(30);check(closing.isAlive()&&!track.released,"Audio teardown waits for output callback");
        AudioTrack.writeContinue.countDown();join(closing);decoder.release();int calls=codec.calls.size();lateCallbacks(codec);decoder.feed(new byte[]{1},0,1,0);
        check(track.released&&codec.released&&codec.calls.size()==calls,"Audio late callbacks suppressed and all resources released");
        AudioTrack.writeEntered=null;AudioTrack.writeContinue=null;
        MediaCodec.failConfigure=true;AudioDecoder partial=new AudioDecoder();boolean failed=false;try{partial.init(48000,2);}catch(IllegalStateException expected){failed=true;}partial.release();check(failed&&latest().released,"Audio partial init cleaned up");
    }
    private static void monitorReplacement()throws Exception{
        MonitorSessionGate.acquire(0);AtomicBoolean entered=new AtomicBoolean();CountDownLatch waiting=new CountDownLatch(1);
        Thread replacement=new Thread(()->{waiting.countDown();try{MonitorSessionGate.acquire(0);entered.set(true);MonitorSessionGate.release(0);}catch(InterruptedException expected){Thread.currentThread().interrupt();}},"replacement");replacement.start();await(waiting);Thread.sleep(30);check(!entered.get(),"Replacement waits for old monitor owner");
        MonitorSessionGate.acquire(1);MonitorSessionGate.release(1);check(!entered.get(),"Other monitor remains independent");
        MonitorSessionGate.release(0);join(replacement);check(entered.get(),"Replacement starts only after old cleanup releases ownership");
        MonitorSessionGate.acquire(0);AtomicBoolean cancelled=new AtomicBoolean();Thread stale=new Thread(()->{try{MonitorSessionGate.acquire(0);MonitorSessionGate.release(0);}catch(InterruptedException expected){cancelled.set(true);}},"stale-replacement");stale.start();stale.interrupt();join(stale);check(cancelled.get(),"Stopped pending replacement exits promptly");MonitorSessionGate.release(0);
    }
    private static void asynchronousSocketShutdown()throws Exception{
        Thread caller=Thread.currentThread();CountDownLatch closeEntered=new CountDownLatch(1),allowClose=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();AtomicBoolean offCaller=new AtomicBoolean(),returned=new AtomicBoolean();
        java.net.Socket socket=new java.net.Socket(){@Override public void close(){calls.incrementAndGet();offCaller.set(Thread.currentThread()!=caller);closeEntered.countDown();MediaCodec.block(null,allowClose);}};
        SocketShutdown shutdown=new SocketShutdown(socket,"socket-close-test");shutdown.request();await(closeEntered);
        check(offCaller.get(),"TLS socket close never executes on requesting/UI thread");shutdown.request();
        Thread cleanup=new Thread(()->{shutdown.await();returned.set(true);},"socket-cleanup");cleanup.start();Thread.sleep(30);
        check(!returned.get(),"Background cleanup retains ownership while socket close is pending");
        cleanup.interrupt();allowClose.countDown();join(cleanup);shutdown.await();
        check(returned.get()&&cleanup.isInterrupted(),"Socket close barrier completes and preserves cancellation");
        check(calls.get()==1,"Repeated stop and cleanup close each socket exactly once");
        AtomicInteger failedCalls=new AtomicInteger();SocketShutdown failing=new SocketShutdown(new java.net.Socket(){@Override public void close()throws java.io.IOException{failedCalls.incrementAndGet();throw new java.io.IOException("injected close failure");}},"socket-close-failure");
        failing.await();failing.await();check(failedCalls.get()==1,"IOException still completes shutdown barrier");
    }
    public static void main(String[] args)throws Exception{
        releaseRecoveryRace();deferredRecovery();partialInitialization();closeDuringInitialization();surfaceDuringInitialization();callbackThreadClose();audioOwnership();monitorReplacement();asynchronousSocketShutdown();
        check(Looper.failure==null,"No fake Android ownership violations");System.out.println("metuxa media lifecycle: "+checks+" checks passed (fake Android, not hardware)");
    }
}
