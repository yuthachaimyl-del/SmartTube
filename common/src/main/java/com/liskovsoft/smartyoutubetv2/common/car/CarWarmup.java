package com.liskovsoft.smartyoutubetv2.common.car;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

/**
 * Car build: prepares the playback token in the background shortly after the app's pages show.
 * Without it the first video after starting the car waits ~3 s while YouTube's check runs.
 */
public final class CarWarmup {
    private static final long REPEAT_AFTER_MS = 20 * 60_000L;
    private static final long DELAY_MS = 4_000L;   // let the home page load first
    private static volatile long sLastMs;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private CarWarmup() {
    }

    public static void playback() {
        long now = SystemClock.elapsedRealtime();
        if (sLastMs != 0 && now - sLastMs < REPEAT_AFTER_MS) {
            return;
        }
        sLastMs = now;
        sHandler.postDelayed(() -> {
            Thread t = new Thread(CarWarmup::run, "car-warmup");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }, DELAY_MS);
    }

    private static void run() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            AppClient client = VideoInfoService.instance().getClient();
            com.liskovsoft.youtubeapi.app.PoTokenGate.getPoToken(client);
            CarPerf.log("warm-up done (" + client + ") in " + (SystemClock.elapsedRealtime() - t0) + "ms");
        } catch (Throwable e) {
            CarPerf.log("warm-up failed: " + e);
            sLastMs = 0;
        }
    }
}
