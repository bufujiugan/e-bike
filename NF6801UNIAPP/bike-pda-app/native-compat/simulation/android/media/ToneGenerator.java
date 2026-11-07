package android.media;
public class ToneGenerator {
    public static final int TONE_PROP_BEEP=24; public static int sounds;
    public ToneGenerator(int stream,int volume) {}
    public boolean startTone(int tone,int duration) { sounds++; return true; }
}
