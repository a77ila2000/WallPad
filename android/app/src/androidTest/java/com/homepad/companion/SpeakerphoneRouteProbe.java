package com.homepad.companion;

import android.app.Instrumentation;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;

/** No microphone/playback, no visitor call and no door command: routing only. */
public final class SpeakerphoneRouteProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        VisitorSpeakerphone[] route = new VisitorSpeakerphone[1];
        AudioManager audio = getTargetContext().getSystemService(AudioManager.class);
        int mode = audio.getMode();
        int volume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        try {
            startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                route[0] = new VisitorSpeakerphone(getTargetContext(), () -> {});
                route[0].open();
            });
            SystemClock.sleep(1500L);
            AudioDeviceInfo device = audio.getCommunicationDevice();
            result.putInt("modeDuring", audio.getMode());
            result.putInt("deviceDuring", device == null ? -1 : device.getType());
            if (audio.getMode() != AudioManager.MODE_IN_COMMUNICATION
                    || device == null || device.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                throw new AssertionError("Actual output route did not select the built-in speaker");
            }
            runOnMainSync(() -> route[0].close());
            result.putInt("modeAfter", audio.getMode());
            result.putInt("volumeAfter", audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
            if (audio.getMode() != mode || audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) != volume) {
                throw new AssertionError("Previous audio state was not restored");
            }
            result.putString("result", "speaker route and restoration PASS");
            finish(-1, result);
        } catch (Throwable error) {
            if (route[0] != null) runOnMainSync(() -> route[0].close());
            result.putString("result", "FAIL: " + error.toString());
            finish(0, result);
        }
    }
}
