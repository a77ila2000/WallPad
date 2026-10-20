package com.homepad.wallpadbridge;

import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Small diagnostic entry point for invoking COMMAX's own read-only frame
 * capture operation from app_process. This is kept separate from the bridge
 * service so a failed vendor call cannot affect the normal wallpad UI.
 */
public final class MediaCaptureMain {
    private MediaCaptureMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].length() == 0) {
            throw new IllegalArgumentException("output path is required");
        }
        // The vendor class creates a Handler while its JNI library is loaded.
        // app_process does not prepare a main looper for plain entry points.
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> service = Class.forName("com.commax.sipua.SipService");
        Method control = service.getMethod("MediaServiceControl", String[].class);
        String path = args[0] + ".0.jpg";
        new File(path).delete();
        try {
            Object result = control.invoke(null, (Object) new String[] {
                    "videoCapture", "", "320", "240", path
            });
            if ("0".equals(String.valueOf(result))) {
                System.out.println("FRAME 0 " + path);
            } else {
                System.out.println("CAPTURE_ERROR " + result);
            }
        } catch (Throwable error) {
            System.out.println("CAPTURE_ERROR " + error.getClass().getSimpleName());
        }
        System.out.flush();
    }
}
