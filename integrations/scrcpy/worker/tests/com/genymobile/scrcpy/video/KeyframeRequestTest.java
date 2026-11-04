package com.genymobile.scrcpy.video;
import android.media.MediaCodec;
public class KeyframeRequestTest {
    public static void main(String[] args) {
        CaptureControl c=new CaptureControl();
        c.requestKeyFrame();
        MediaCodec codec=new MediaCodec();
        c.setRunningMediaCodec(codec);
        c.requestKeyFrame();
        if (codec.requests!=1 || codec.resets!=0 || c.isResetRequested()) throw new AssertionError();
        codec.reject=true;
        c.requestKeyFrame(); // Unsupported hints do not destroy healthy capture.
        if (c.isResetRequested()) throw new AssertionError();
        codec.reject=false;
        c.reset(CaptureControl.RESET_REASON_CLIENT_RESET);
        c.requestKeyFrame();
        if (codec.requests!=1 || codec.resets!=1) throw new AssertionError();
        c.consumeReset();
        c.setRunningMediaCodec(null);
        c.requestKeyFrame();
        if (codec.requests!=1) throw new AssertionError();
        System.out.println("KeyframeRequest: running, rejected, reset and stopped cases passed");
    }
}
