package android.media;
public final class AudioFormat {public static final int CHANNEL_OUT_STEREO=2,CHANNEL_OUT_MONO=1,ENCODING_PCM_16BIT=16;public static final class Builder{public Builder setSampleRate(int value){return this;}public Builder setEncoding(int value){return this;}public Builder setChannelMask(int value){return this;}public AudioFormat build(){return new AudioFormat();}}}
