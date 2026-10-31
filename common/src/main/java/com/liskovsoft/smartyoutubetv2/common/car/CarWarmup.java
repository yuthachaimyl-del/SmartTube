package com.liskovsoft.smartyoutubetv2.common.car;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

/**
 * Car build: gets the first video ready in the background shortly after the app's pages show.
 * <ul>
 * <li>The playback token: without it the first video after starting the car waits ~3 s while YouTube's check runs.</li>
 * <li>The stream info of the video the home page shows first. The first stream info after a start also fetches
 * YouTube's subtitle language list (one more request) and loads the player script - about a second.
 * Done now, the video the user picks starts without that (and the shown video itself right away).</li>
 * </ul>
 */
public final class CarWarmup {
    private static final long REPEAT_AFTER_MS = 20 * 60_000L;
    private static final long DELAY_MS = 4_000L;   // let the home page load first
    private static volatile long sLastMs;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private CarWarmup() {
    }

    public static void playback(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        if (sLastMs != 0 && now - sLastMs < REPEAT_AFTER_MS) {
            return;
        }
        sLastMs = now;
        sHandler.postDelayed(() -> {
            String videoId = pickVideo(activity);
            Thread t = new Thread(() -> run(videoId), "car-warmup");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }, DELAY_MS);
    }

    /** The video the home page shows first, but only while nothing has been played (its info stays cached). */
    private static String pickVideo(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || CarPerf.isAnyVideoStarted()
                    || PlaybackPresenter.instance(activity).getVideo() != null) {
                return null;
            }
            Video video = BrowsePresenter.instance(activity).getCurrentVideo();
            return video != null && video.videoId != null && !video.isLive && !video.isUpcoming ? video.videoId : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void run(String videoId) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            AppClient client = VideoInfoService.instance().getClient();
            com.liskovsoft.youtubeapi.app.PoTokenGate.getPoToken(client);
            CarPerf.log("warm-up done (" + client + ") in " + (SystemClock.elapsedRealtime() - t0) + "ms");
        } catch (Throwable e) {
            CarPerf.log("warm-up failed: " + e);
            sLastMs = 0;
            return;
        }

        if (videoId == null || CarPerf.isAnyVideoStarted()) {
            return;
        }

        long t1 = SystemClock.elapsedRealtime();
        try {
            YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(videoId);
            CarPerf.log("warm-up stream info in " + (SystemClock.elapsedRealtime() - t1) + "ms");
        } catch (Throwable e) {
            CarPerf.log("warm-up stream info failed: " + e);
        }
    }
}
