package com.liskovsoft.smartyoutubetv2.common.car;

import android.util.Log;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * Car build: the car APK is built as a debug build, and a debug build adds two logging steps to the
 * shared network client. Both download every response completely and print it to the system log
 * before the app gets to see it - video data included. So the player could not start until a whole
 * multi-second piece of video had arrived, and pages loaded slower. This removes them once, before the
 * app starts using the network.
 */
public final class CarNet {
    private static final String TAG = "CarPerf";
    private static boolean sDone;

    private CarNet() {
    }

    public static synchronized void removeDebugLogging() {
        if (sDone) {
            return;
        }
        sDone = true;

        try {
            // The first call creates the shared manager: create it without the network profiler
            OkHttpManager manager = OkHttpManager.instance(false);
            OkHttpClient.Builder builder = manager.getClient().newBuilder();
            int removed = removeLoggers(builder.interceptors()) + removeLoggers(builder.networkInterceptors());

            if (removed > 0) {
                Field field = OkHttpManager.class.getDeclaredField("mClient");
                field.setAccessible(true);
                field.set(manager, builder.build());
            }

            Log.i(TAG, "network debug logging removed: " + removed);
        } catch (Throwable e) {
            Log.e(TAG, "network debug logging not removed", e);
        }
    }

    private static int removeLoggers(List<Interceptor> interceptors) {
        int removed = 0;

        for (Iterator<Interceptor> it = interceptors.iterator(); it.hasNext(); ) {
            String name = it.next().getClass().getName();
            if (name.startsWith("okhttp3.logging.") || name.toLowerCase().contains("okhttpprofiler")) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }
}
