package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Car build: the "next videos" list split into tabs - แนะนำ (suggested videos), เพลย์ลิสต์ (the playlist / queue the
 * video was opened from) and ตอน (the video's chapters). Used by the list on the main screen and on screen 2.
 */
public final class NextTabs implements SidePanel.Tabs {
    public static final int SUGGESTED = 0, PLAYLIST = 1, CHAPTERS = 2;
    private static final String[] TITLES = {"แนะนำ", "เพลย์ลิสต์", "ตอน"};
    private static final String[] EMPTY = {
            "ยังไม่มีคลิปแนะนำ  แตะ 🔍 เพื่อค้นหา",
            "คลิปนี้ไม่ได้เปิดจากเพลย์ลิสต์",
            "คลิปนี้ไม่ได้แบ่งตอน"
    };

    /** All videos of the rows under the player, in row order. */
    public interface Source {
        List<Video> all();
        /** The video that is playing (its playlist decides what goes under เพลย์ลิสต์). */
        Video playing();
    }

    private final Source mSource;
    private int mTab = SUGGESTED;

    public NextTabs(Source source) {
        mSource = source;
    }

    @Override
    public String[] titles() {
        return TITLES;
    }

    @Override
    public void onTabSelected(int index, SidePanel panel) {
        mTab = index;
        panel.setItems(filter(mSource.all(), mSource.playing(), index), EMPTY[index]);
    }

    /** The list opens on แนะนำ; when that is empty, on the first tab that has something. */
    @Override
    public int tabOnOpen() {
        List<Video> all = mSource.all();
        Video playing = mSource.playing();
        mTab = SUGGESTED;
        if (filter(all, playing, SUGGESTED).isEmpty()) {
            for (int t = 0; t < TITLES.length; t++) {
                if (!filter(all, playing, t).isEmpty()) {
                    mTab = t;
                    break;
                }
            }
        }
        return mTab;
    }

    @Override
    public String emptyText() {
        return EMPTY[mTab];
    }

    /** The videos of the tab that is shown now. */
    public List<Video> current() {
        return filter(mSource.all(), mSource.playing(), mTab);
    }

    static List<Video> filter(List<Video> all, Video playing, int tab) {
        List<Video> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        String list = playing != null ? playing.getPlaylistId() : null;
        if (list != null && list.isEmpty()) {
            list = null;
        }
        Map<VideoGroup, Integer> groupKind = new IdentityHashMap<>();
        for (Video v : all) {
            if (v != null && kind(v, list, groupKind) == tab) {
                out.add(v);
            }
        }
        return out;
    }

    private static int kind(Video v, String list, Map<VideoGroup, Integer> cache) {
        if (v.isChapter) {
            return CHAPTERS;
        }
        VideoGroup g = v.getGroup();
        if (g == null) {
            return list != null && list.equals(v.getPlaylistId()) ? PLAYLIST : SUGGESTED;
        }
        Integer k = cache.get(g);
        if (k == null) {
            k = groupKind(g, list);
            cache.put(g, k);
        }
        return k;
    }

    /**
     * A whole row is one kind: the play queue, or the row of the playlist the playing video came from (its videos
     * share that playlist), or suggestions.
     */
    private static int groupKind(VideoGroup g, String list) {
        if (g.getType() == MediaGroup.TYPE_PLAYBACK_QUEUE) {
            return PLAYLIST;
        }
        List<Video> videos = g.getVideos();
        if (videos == null || videos.isEmpty()) {
            return SUGGESTED;
        }
        int chapters = 0, inPlaylist = 0;
        for (Video v : videos) {
            if (v.isChapter) {
                chapters++;
            } else if (list != null && list.equals(v.getPlaylistId())) {
                inPlaylist++;
            }
        }
        if (chapters * 2 >= videos.size()) {
            return CHAPTERS;
        }
        return inPlaylist * 2 >= videos.size() ? PLAYLIST : SUGGESTED;
    }
}
