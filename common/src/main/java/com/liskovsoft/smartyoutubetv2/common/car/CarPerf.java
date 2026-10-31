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

    private CarPerf() {
    }

    public static void start(String what) {
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

    public static void log(String msg) {
        Log.i(TAG, msg);
    }
}
