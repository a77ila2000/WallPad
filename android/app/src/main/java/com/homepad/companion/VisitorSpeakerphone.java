package com.homepad.companion;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/** Owns the phone's communication route only for the duration of a visitor call. */
final class VisitorSpeakerphone {
    private final AudioManager audio;
    private final int previousMode;
    private final boolean previousSpeaker;
    private final int previousVolume;
    private AudioFocusRequest focus;
    private final Runnable focusLost;
    private int raisedVolume = -1;
    private boolean closed;

    VisitorSpeakerphone(Context context, Runnable focusLost) {
        this.focusLost = focusLost;
        audio = context.getSystemService(AudioManager.class);
        previousMode = audio.getMode();
        previousSpeaker = audio.isSpeakerphoneOn();
        previousVolume = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
    }

    void open() {
        if (previousMode == AudioManager.MODE_IN_CALL || previousMode == AudioManager.MODE_IN_COMMUNICATION
                || previousMode == AudioManager.MODE_RINGTONE) {
            throw new IllegalStateException("전화 통화가 끝난 뒤 방문자 통화를 받아 주세요");
        }
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        focusLost.run();
                    }
                })
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build();
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw new IllegalStateException("통화 오디오를 사용하지 못했습니다");
        }
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= 31) {
            boolean selected = false;
            for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    selected = audio.setCommunicationDevice(device);
                    break;
                }
            }
            if (!selected) throw new IllegalStateException("스피커폰 출력 경로를 열지 못했습니다");
        } else {
            audio.setSpeakerphoneOn(true);
        }
        // A silent/very low call-volume preference must not hide the visitor's
        // voice. Volume buttons can still change it during this call.
        int minimum = (int) Math.ceil(audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * 0.7);
        if (audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < minimum) {
            raisedVolume = minimum;
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, minimum, 0);
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        // Do not overwrite a cellular call's routing if one took over.
        if (audio.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
            if (raisedVolume >= 0 && audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == raisedVolume) {
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previousVolume, 0);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice();
            } else audio.setSpeakerphoneOn(previousSpeaker);
            audio.setMode(previousMode);
        }
        if (focus != null) audio.abandonAudioFocusRequest(focus);
    }
}
