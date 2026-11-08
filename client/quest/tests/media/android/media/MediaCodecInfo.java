package android.media;
public final class MediaCodecInfo {
    public CodecCapabilities getCapabilitiesForType(String mime){return new CodecCapabilities();}
    public static final class CodecCapabilities {public static final String FEATURE_LowLatency="low-latency";public boolean isFeatureSupported(String feature){return true;}}
}
