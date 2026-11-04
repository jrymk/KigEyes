package android.media;
import android.os.Bundle;
public class MediaCodec {
    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME="request-sync";
    public int requests, resets;
    public boolean reject;
    public void setParameters(Bundle b) {
        if (reject) throw new IllegalStateException();
        if (!PARAMETER_KEY_REQUEST_SYNC_FRAME.equals(b.key) || b.value!=0) throw new AssertionError();
        requests++;
    }
    public void signalEndOfInputStream() { resets++; }
}
