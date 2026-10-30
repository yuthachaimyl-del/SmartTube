package com.liskovsoft.smartyoutubetv2.common.car;

import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/** Car build: loads a browse section (Home, Subscriptions, History...) as one flat list of videos. */
public final class CarSections {
    public interface Callback {
        void onResult(List<Video> videos, String error);
    }

    public static final String[] TITLES = {"หน้าแรก", "ติดตาม", "ประวัติ", "เพลง", "ข่าว", "เกม", "ไลฟ์"};
    private static final int MAX_VIDEOS = 120;

    private CarSections() {
    }

    public static Disposable load(int index, Callback callback) {
        ContentService s = YouTubeServiceManager.instance().getContentService();
        Handler main = new Handler(Looper.getMainLooper());
        List<Video> out = new ArrayList<>();
        try {
            switch (index) {
                case 1:
                    return s.getSubscriptionsObserve().subscribe(
                            g -> { add(out, g); post(main, callback, out, null); },
                            e -> post(main, callback, out, "โหลดไม่ได้ (ต้องล็อกอินก่อน?)"));
                case 2:
                    return s.getHistoryObserve().subscribe(
                            g -> { add(out, g); post(main, callback, out, null); },
                            e -> post(main, callback, out, "โหลดไม่ได้ (ต้องล็อกอินก่อน?)"));
                default:
                    io.reactivex.Observable<List<MediaGroup>> rows =
                            index == 3 ? s.getMusicObserve() :
                            index == 4 ? s.getNewsObserve() :
                            index == 5 ? s.getGamingObserve() :
                            index == 6 ? s.getLiveObserve() : s.getHomeObserve();
                    return rows.subscribe(
                            groups -> {
                                if (groups != null) {
                                    for (MediaGroup g : groups) {
                                        add(out, g);
                                    }
                                }
                                post(main, callback, new ArrayList<>(out), null);
                            },
                            e -> post(main, callback, out, "โหลดไม่ได้"));
            }
        } catch (Exception e) {
            post(main, callback, out, "โหลดไม่ได้");
            return null;
        }
    }

    private static void add(List<Video> out, MediaGroup g) {
        if (g == null || out.size() >= MAX_VIDEOS) {
            return;
        }
        VideoGroup vg = VideoGroup.from(g);
        if (vg == null || vg.getVideos() == null) {
            return;
        }
        for (Video v : vg.getVideos()) {
            if (out.size() >= MAX_VIDEOS) {
                break;
            }
            if (v != null && v.videoId != null) {
                out.add(v);
            }
        }
    }

    private static void post(Handler main, Callback cb, List<Video> videos, String error) {
        main.post(() -> cb.onResult(videos, error));
    }
}
