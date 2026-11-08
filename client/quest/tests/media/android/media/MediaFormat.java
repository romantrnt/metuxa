package android.media;
import java.nio.ByteBuffer;
import java.util.HashMap;
public final class MediaFormat {
    public static final String MIMETYPE_VIDEO_HEVC="hevc",MIMETYPE_VIDEO_AVC="avc",MIMETYPE_AUDIO_OPUS="opus",KEY_WIDTH="width",KEY_HEIGHT="height",KEY_MAX_INPUT_SIZE="max",KEY_LOW_LATENCY="latency";
    private final HashMap<String,Integer> integers=new HashMap<>();
    public static MediaFormat createVideoFormat(String mime,int width,int height){MediaFormat format=new MediaFormat();format.setInteger(KEY_WIDTH,width);format.setInteger(KEY_HEIGHT,height);return format;}
    public static MediaFormat createAudioFormat(String mime,int rate,int channels){return new MediaFormat();}
    public void setInteger(String key,int value){integers.put(key,value);}
    public int getInteger(String key){return integers.get(key);}
    public boolean containsKey(String key){return integers.containsKey(key);}
    public void setByteBuffer(String key,ByteBuffer buffer){}
}
