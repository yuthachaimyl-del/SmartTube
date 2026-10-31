package com.liskovsoft.smartyoutubetv2.common.car;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection.Definition;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Car build: "Auto" picture and sound.
 * <p>
 * Picture: one quality per video, chosen at the start - the best one this unit decodes in hardware without
 * dropping frames and the network carries. (The streaming method YouTube uses here can't change quality in the
 * middle of a stream: ExoPlayer's moment-to-moment switching only re-downloaded the start of the video and
 * caused stutters.) When frames drop or playback keeps stalling, {@link CarPlaybackMonitor} steps down once and
 * the lesson is kept, so the next videos start at a level that plays smoothly.
 * <p>
 * Sound: the best original stream as it is - the highest bit rate, no YouTube "stable volume" (DRC) version,
 * stereo, Opus first (48 kHz like the output: no resampling). Volume effects are off ({@link #isPureAudio}).
 */
public final class CarAutoQuality {
    private static final String PREFS = "car_auto_quality";
    private static final String KEY_ESTIMATE = "bandwidth";
    private static final String KEY_BAD = "too_heavy_";
    private static final String KEY_BAD_TIME = "too_heavy_time_";
    private static final long REMEMBER_MS = 7L * 24 * 60 * 60_000; // try the heavier level again after a week
    private static final int MAX_SHORT_SIDE = 1080; // car screens: 1920x720 and 2400x896
    private static final long FIRST_ESTIMATE = 3_000_000; // nothing measured yet: about 720p
    private static final float BANDWIDTH_SHARE = 0.75f;
    private static SharedPreferences sPrefs;
    private static volatile boolean sMeterWarm;
    @Nullable private static TrackGroupArray sVideoGroups; // the video the per-video limits belong to
    private static int sVideoBitrateCap = Integer.MAX_VALUE;
    @Nullable private static volatile Format sVideo;
    @Nullable private static volatile Format sAudio;

    private CarAutoQuality() {
    }

    public static void init(Context context) {
        if (sPrefs == null && context != null) {
            sPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** Sound straight through: no loudness effect, no volume normalization. On with auto sound. */
    public static boolean isPureAudio() {
        return com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.AdaptiveQuality.isAudioAuto();
    }

    // ---------------------------------------- picture ----------------------------------------

    @Nullable
    public static synchronized Definition pickVideo(TrackGroupArray groups, int[][] supports, long meterEstimate) {
        if (!groups.equals(sVideoGroups)) { // another video: forget the limits of the last one (a re-selection gets a new
            // array with the same tracks)
            sVideoGroups = groups;
            sVideoBitrateCap = Integer.MAX_VALUE;
        }

        List<int[]> all = new ArrayList<>();
        for (int g = 0; g < groups.length && g < supports.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length && t < supports[g].length; t++) {
                Format f = group.getFormat(t);
                if (isHandled(supports[g][t]) && f.width > 0 && f.height > 0) {
                    all.add(new int[] {g, t});
                }
            }
        }
        if (all.isEmpty()) {
            return null;
        }

        List<int[]> c = all;
        c = keep(c, groups, f -> CarCodecs.hasHardwareDecoder(CarCodecs.mimeOf(f)));
        c = keep(c, groups, f -> !isTenBit(f)); // HDR versions: car screens are SDR
        c = keep(c, groups, f -> shortSide(f) <= MAX_SHORT_SIDE);
        c = keep(c, groups, f -> !Boolean.FALSE.equals(
                CarCodecs.supportsSmooth(CarCodecs.mimeOf(f), f.width, f.height, fps(f))));
        c = keep(c, groups, f -> !isTooHeavy(f));
        final int cap = sVideoBitrateCap;
        c = keep(c, groups, f -> f.bitrate <= 0 || f.bitrate <= cap);

        long estimate = meterEstimate > 0 && sMeterWarm ? meterEstimate : savedEstimate();
        long budget = (long) (estimate * BANDWIDTH_SHARE);
        List<int[]> fit = filter(c, groups, f -> f.bitrate <= 0 || f.bitrate <= budget);

        int[] best = null;
        if (fit.isEmpty()) { // the network is slower than everything: the lightest one
            for (int[] gt : c) {
                if (best == null || format(groups, gt).bitrate < format(groups, best).bitrate) {
                    best = gt;
                }
            }
        } else {
            for (int[] gt : fit) {
                if (best == null || better(format(groups, gt), format(groups, best))) {
                    best = gt;
                }
            }
        }

        Format f = format(groups, best);
        sVideo = f;
        CarPerf.log(String.format(Locale.US, "auto picture: %s (network ~%.1f Mbps%s, %d versions)",
                describe(f), estimate / 1_000_000f, sMeterWarm ? "" : " remembered", all.size()));
        return new Definition(groups.get(best[0]), best[1]);
    }

    /** Higher resolution, then higher frame rate, then the codec that looks best per bit. */
    private static boolean better(Format a, Format b) {
        if (shortSide(a) != shortSide(b)) {
            return shortSide(a) > shortSide(b);
        }
        if (Math.round(fps(a)) != Math.round(fps(b))) {
            return fps(a) > fps(b);
        }
        if (codecRank(a) != codecRank(b)) {
            return codecRank(a) > codecRank(b);
        }
        return a.bitrate > b.bitrate;
    }

    /** VP9 first (same picture for less data), then H.264 (decoded everywhere), AV1 last (rarely in hardware). */
    private static int codecRank(Format f) {
        String family = family(f);
        return "vp9".equals(family) ? 3 : "avc".equals(family) ? 2 : "av1".equals(family) ? 1 : 0;
    }

    /** Frames dropped at this level: don't use it (this codec, this much picture per second) for a week. */
    public static synchronized void onFramesDropping(@Nullable Format f) {
        if (f == null || sPrefs == null) {
            return;
        }
        String family = family(f);
        float load = load(f);
        float old = sPrefs.getFloat(KEY_BAD + family, Float.MAX_VALUE);
        sPrefs.edit()
                .putFloat(KEY_BAD + family, Math.min(old, load))
                .putLong(KEY_BAD_TIME + family, System.currentTimeMillis())
                .apply();
        CarPerf.log("frames dropping at " + describe(f) + ": use lighter " + family + " from now on");
    }

    /** Playback keeps stalling: below this bit rate for the rest of the video, and start lower next time. */
    public static synchronized void onStalls(@Nullable Format f) {
        if (f == null || f.bitrate <= 0) {
            return;
        }
        sVideoBitrateCap = f.bitrate - 1;
        saveEstimate((long) (f.bitrate / BANDWIDTH_SHARE * 0.8f));
        CarPerf.log("stalls at " + describe(f) + ": lower for this video");
    }

    /** Auto picked again by the user: start over (try the best levels again). */
    public static synchronized void resetLearned() {
        if (sPrefs != null) {
            SharedPreferences.Editor editor = sPrefs.edit();
            for (String key : sPrefs.getAll().keySet()) {
                if (key.startsWith(KEY_BAD)) {
                    editor.remove(key);
                }
            }
            editor.apply();
        }
        sVideoBitrateCap = Integer.MAX_VALUE;
    }

    private static boolean isTooHeavy(Format f) {
        if (sPrefs == null) {
            return false;
        }
        String family = family(f);
        long time = sPrefs.getLong(KEY_BAD_TIME + family, 0);
        if (time == 0 || System.currentTimeMillis() - time > REMEMBER_MS) {
            return false;
        }
        return load(f) >= sPrefs.getFloat(KEY_BAD + family, Float.MAX_VALUE) * 0.95f;
    }

    // ---------------------------------------- network ----------------------------------------

    /** The bandwidth meter has real measurements of this session (a video played a while). */
    public static void onMeterWarm(long estimate) {
        sMeterWarm = true;
        if (estimate > 0) {
            saveEstimate(estimate);
        }
    }

    private static long savedEstimate() {
        long saved = sPrefs != null ? sPrefs.getLong(KEY_ESTIMATE, 0) : 0;
        return saved > 0 ? saved : FIRST_ESTIMATE;
    }

    private static void saveEstimate(long estimate) {
        if (sPrefs != null && estimate > 0) {
            sPrefs.edit().putLong(KEY_ESTIMATE, estimate).apply();
        }
    }

    // ---------------------------------------- sound ----------------------------------------

    @Nullable
    public static Definition pickAudio(TrackGroupArray groups, int[][] supports, @Nullable String language) {
        List<int[]> all = new ArrayList<>();
        for (int g = 0; g < groups.length && g < supports.length; g++) {
            TrackGroup group = groups.get(g);
            for (int t = 0; t < group.length && t < supports[g].length; t++) {
                if (isHandled(supports[g][t])) {
                    all.add(new int[] {g, t});
                }
            }
        }
        if (all.isEmpty()) {
            return null;
        }

        List<int[]> c = all;
        if (language != null && !language.isEmpty()) {
            c = keep(c, groups, f -> f.language != null && f.language.toLowerCase(Locale.ROOT)
                    .startsWith(language.toLowerCase(Locale.ROOT)));
        } else {
            c = keep(c, groups, f -> f.language != null && f.language.toLowerCase(Locale.ROOT).contains("original"));
        }
        c = keep(c, groups, f -> f.language == null || !f.language.toLowerCase(Locale.ROOT).contains("descriptive"));
        c = keep(c, groups, f -> !isDrc(f)); // "stable volume" versions are compressed
        c = keep(c, groups, f -> !isMultichannel(f)); // stereo system: no downmix

        int[] best = null;
        for (int[] gt : c) {
            if (best == null || audioScore(format(groups, gt)) > audioScore(format(groups, best))) {
                best = gt;
            }
        }

        Format f = format(groups, best);
        sAudio = f;
        CarPerf.log("auto sound: " + describeAudio(f) + " (" + all.size() + " versions)");
        return new Definition(groups.get(best[0]), best[1]);
    }

    /** Bit rate, with Opus a bit ahead: it's 48 kHz like the output, so nothing gets resampled. */
    private static float audioScore(Format f) {
        float bitrate = f.bitrate > 0 ? f.bitrate : 64_000;
        return MimeTypes.AUDIO_OPUS.equals(f.sampleMimeType) ? bitrate * 1.15f : bitrate;
    }

    private static boolean isDrc(Format f) {
        return f.isDrc || (f.id != null && f.id.toLowerCase(Locale.ROOT).endsWith("drc"));
    }

    private static boolean isMultichannel(Format f) {
        return f.channelCount > 2 || MimeTypes.AUDIO_AC3.equals(f.sampleMimeType)
                || MimeTypes.AUDIO_E_AC3.equals(f.sampleMimeType) || f.bitrate > 300_000;
    }

    // ---------------------------------------- shown in the player ----------------------------------------

    /** e.g. "1080p60 VP9" - what auto plays now. */
    @Nullable
    public static String currentVideo() {
        Format f = sVideo;
        return f != null ? describe(f) : null;
    }

    /** e.g. "Opus 160 kbps" - what auto plays now. */
    @Nullable
    public static String currentAudio() {
        Format f = sAudio;
        return f != null ? describeAudio(f) : null;
    }

    private static String describe(Format f) {
        int fps = Math.round(fps(f));
        return shortSide(f) + "p" + (fps > 30 ? String.valueOf(fps) : "") + " " + family(f).toUpperCase(Locale.ROOT)
                + (f.bitrate > 0 ? String.format(Locale.US, " %.1f Mbps", f.bitrate / 1_000_000f) : "");
    }

    private static String describeAudio(Format f) {
        String codec = MimeTypes.AUDIO_OPUS.equals(f.sampleMimeType) ? "Opus"
                : MimeTypes.AUDIO_AAC.equals(f.sampleMimeType) ? "AAC" : String.valueOf(f.sampleMimeType);
        return codec + (f.bitrate > 0 ? " " + Math.round(f.bitrate / 1000f) + " kbps" : "")
                + (f.sampleRate > 0 ? " " + (f.sampleRate / 1000f) + " kHz" : "");
    }

    // ---------------------------------------- helpers ----------------------------------------

    private interface Test {
        boolean test(Format f);
    }

    /** The tracks that pass, or all of them when none would (a preference, not a must). */
    private static List<int[]> keep(List<int[]> list, TrackGroupArray groups, Test test) {
        List<int[]> out = filter(list, groups, test);
        return out.isEmpty() ? list : out;
    }

    private static List<int[]> filter(List<int[]> list, TrackGroupArray groups, Test test) {
        List<int[]> out = new ArrayList<>();
        for (int[] gt : list) {
            if (test.test(format(groups, gt))) {
                out.add(gt);
            }
        }
        return out;
    }

    private static Format format(TrackGroupArray groups, int[] gt) {
        return groups.get(gt[0]).getFormat(gt[1]);
    }

    private static boolean isHandled(int support) {
        return (support & RendererCapabilities.FORMAT_SUPPORT_MASK) == RendererCapabilities.FORMAT_HANDLED;
    }

    private static String family(Format f) {
        String mime = CarCodecs.mimeOf(f);
        if (mime == null) {
            return "video";
        }
        mime = mime.toLowerCase(Locale.ROOT);
        return mime.contains("avc") ? "avc" : mime.contains("vp9") ? "vp9" : mime.contains("av01") ? "av1"
                : mime.contains("hevc") ? "hevc" : mime.replace("video/", "");
    }

    private static float fps(Format f) {
        return f.frameRate > 0 ? f.frameRate : 30;
    }

    private static int shortSide(Format f) {
        return Math.min(f.width, f.height);
    }

    /** Picture per second: width x height x frames. */
    private static float load(Format f) {
        return (float) f.width * f.height * fps(f);
    }

    private static boolean isTenBit(Format f) {
        String codecs = f.codecs != null ? f.codecs.toLowerCase(Locale.ROOT) : "";
        if (codecs.startsWith("vp09.02") || codecs.startsWith("vp09.03")
                || (codecs.startsWith("av01") && codecs.contains(".10."))) {
            return true;
        }
        return f.colorInfo != null && (f.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                || f.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG);
    }
}
