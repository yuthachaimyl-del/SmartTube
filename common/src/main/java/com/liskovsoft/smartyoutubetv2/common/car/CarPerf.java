package com.liskovsoft.smartyoutubetv2.common.car;

import android.os.SystemClock;
import android.util.Log;

/**
 * Car build: times the steps from tapping a video to its first picture (logcat tag "CarPerf"),
 * so slow steps can be found and fixed.
 */
public final class CarPerf {
    private static final String TAG = "CarPerf";
    private static volatile long sStartMs;
    private static volatile boolean sFirstFrameSeen;
    private static volatile boolean sAnyStart;

    private CarPerf() {
    }

    private static boolean sTestClientDone;

    /**
     * Test hook (adb shell setprop log.tag.CarDash DEBUG, then start the app): the first video goes through another
     * YouTube client, like after the app's own error recovery. Those clients deliver DASH instead of SABR, so that way
     * of playing can be tested on purpose.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void maybeUseTestClient() {
        if (sTestClientDone) {
            return;
        }
        sTestClientDone = true;
        if (!Log.isLoggable("CarDash", Log.DEBUG)) {
            return;
        }
        try {
            Class<?> serviceClass = Class.forName("com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService");
            Object service = serviceClass.getMethod("instance").invoke(null);
            Class clientClass = Class.forName("com.liskovsoft.youtubeapi.common.helpers.AppClient");
            java.lang.reflect.Field next = serviceClass.getDeclaredField("mNextClient");
            next.setAccessible(true);
            next.set(service, Enum.valueOf(clientClass, "TV_DOWNGRADED"));
            Log.i(TAG, "test: this start uses the TV_DOWNGRADED client");
        } catch (Throwable e) {
            Log.i(TAG, "test client not set: " + e);
        }
    }

    /**
     * Test hook (adb shell setprop log.tag.CarSabr DEBUG, then start the app): play through SABR whenever YouTube offers
     * it, also when it offers DASH as well (the app takes DASH first then).
     */
    public static boolean isSabrTest() {
        return Log.isLoggable("CarSabr", Log.DEBUG);
    }

    public static void start(String what) {
        maybeUseTestClient();
        sAnyStart = true;
        sStartMs = SystemClock.elapsedRealtime();
        sFirstFrameSeen = false;
        Log.i(TAG, "start: " + what);
    }

    public static void mark(String step) {
        long start = sStartMs;
        if (start > 0) {
            Log.i(TAG, step + " +" + (SystemClock.elapsedRealtime() - start) + "ms");
        }
    }

    /** Only the first picture after a start counts. */
    public static void firstFrame() {
        if (!sFirstFrameSeen) {
            sFirstFrameSeen = true;
            mark("first frame");
        }
    }

    /** Whether a video was opened since the app started. */
    public static boolean isAnyVideoStarted() {
        return sAnyStart;
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
    }
}
