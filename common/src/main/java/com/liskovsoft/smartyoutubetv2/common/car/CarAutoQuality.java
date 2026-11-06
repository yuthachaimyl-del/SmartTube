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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Car build: "Auto" picture and sound.
 * <p>
 * Picture: one quality per video, chosen at the start - the best one this unit decodes in hardware without
 * dropping frames and the network carries. The other versions of the same codec ride along in the selection
 * ({@link CarTrackSelection}), so the picture can change in the middle of the stream without stopping: when the
 * network has had clear headroom for a while it goes back up at the next piece of video; when the decoder drops
 * frames it goes down the same way. (ExoPlayer's own moment-to-moment switching isn't used: it isn't aware of the
 * decoder's limits, and with YouTube's streaming method it used to re-download the start of the video.) When
 * playback keeps stalling or doesn't start while data is still arriving, {@link CarPlaybackMonitor} steps down
 * right away - the stream is rebuilt so the slow download is dropped - again if needed, and the lesson is kept, so
 * the next videos start at a level that plays smoothly.
 * <p>
 * Sound: the best original stream as it is - the highest bit rate, no YouTube "stable volume" (DRC) version,
 * stereo, Opus first (48 kHz like the output: no resampling). Volume effects are off ({@link #isPureAudio}).
 */
public final class CarAutoQuality {
    private static final String PREFS = "car_auto_quality";
    private static final String KEY_ESTIMATE = "bandwidth";
    private static final String KEY_ESTIMATE_TIME = "bandwidth_time";
    private static final long ESTIMATE_FRESH_MS = 15 * 60_000; // an older measurement says little about where the car is now
    private static final String KEY_BAD = "too_heavy_";
    private static final String KEY_BAD_TIME = "too_heavy_time_";
    private static final long REMEMBER_MS = 7L * 24 * 60 * 60_000; // try the heavier level again after a week
    private static final int MAX_SHORT_SIDE = 1080; // car screens: 1920x720 and 2400x896
    private static final long FIRST_ESTIMATE = 3_000_000; // nothing measured yet: about 720p
    private static final float BANDWIDTH_SHARE = 0.75f;
    /** Below this budget the network is weak: lighter sound too, so the picture gets the bandwidth. */
    private static final long WEAK_BUDGET = 800_000;
    private static volatile boolean sWeak;
    private static SharedPreferences sPrefs;
    private static volatile boolean sMeterWarm;
    private static volatile long sMeterWarmAtMs; // elapsedRealtime of the last real measurement
    /** The video pick of this selection pass decided {@link #sWeak} (false: auto picture is off, decide from the meter). */
    private static volatile boolean sWeakFromVideo;
    @Nullable private static TrackGroupArray sVideoGroups; // the video the per-video limits belong to
    private static int sVideoBitrateCap = Integer.MAX_VALUE;
    /** The versions of the current video that pass every filter but the bit rate ones (same order as the groups). */
    private static final List<Format> sCandidates = new ArrayList<>();
    @Nullable private static volatile Format sVideo;
    @Nullable private static volatile Format sAudio;
    /** Bumped when the next selection must rebuild the video stream (a stall: drop the slow download at once). */
    private static int sGeneration;
    /** The selection playing now, for a change of version in the middle of the stream. */
    private static volatile WeakReference<CarTrackSelection> sLive = new WeakReference<>(null);

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
        // The decoder's own numbers: what it says it plays smoothly. Some units state too little - then up to 720p is
        // tried anyway (the dropped-frame watch steps down if it really can't), rather than 480p for every 60 fps video.
        List<int[]> declared = filter(c, groups, f -> !Boolean.FALSE.equals(
                CarCodecs.supportsSmooth(CarCodecs.mimeOf(f), f.width, f.height, fps(f))));
        if (!declared.isEmpty() && maxShortSide(declared, groups) >= 720) {
            c = declared;
        } else {
            c = keep(c, groups, f -> shortSide(f) <= 720 || !Boolean.FALSE.equals(
                    CarCodecs.supportsSmooth(CarCodecs.mimeOf(f), f.width, f.height, fps(f))));
        }
        c = keep(c, groups, f -> !isTooHeavy(f));
        sCandidates.clear();
        for (int[] gt : c) {
            sCandidates.add(format(groups, gt));
        }
        final int cap = sVideoBitrateCap;
        c = keep(c, groups, f -> f.bitrate <= 0 || f.bitrate <= cap);

        // Nothing measured in this session yet (car just started, maybe somewhere with a weak signal): start no higher
        // than the first-time level (~720p), whatever an earlier drive on a fast road measured. Once a video has
        // played a while, the live measurement takes over (while it's recent: a process can sit in the background
        // for hours).
        boolean live = meterEstimate > 0 && isMeterWarm();
        long estimate = live ? meterEstimate : Math.min(savedEstimate(), FIRST_ESTIMATE);
        final long budget = cap != Integer.MAX_VALUE ? Math.min((long) (estimate * BANDWIDTH_SHARE), cap)
                : (long) (estimate * BANDWIDTH_SHARE);
        sWeak = budget < WEAK_BUDGET;
        sWeakFromVideo = true;
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
        // The other versions of the same codec in the same group come along, for a change in mid-stream
        List<Integer> tracks = new ArrayList<>();
        for (Format candidate : sCandidates) {
            int index = groups.get(best[0]).indexOf(candidate);
            if (index >= 0 && family(candidate).equals(family(f))) {
                tracks.add(index);
            }
        }
        if (!tracks.contains(best[1])) {
            tracks.add(best[1]);
        }
        int[] trackArray = new int[tracks.size()];
        for (int i = 0; i < trackArray.length; i++) {
            trackArray[i] = tracks.get(i);
        }
        CarPerf.log(String.format(Locale.US, "auto picture: %s (network ~%.1f Mbps%s, %d versions, %d switchable)",
                describe(f), estimate / 1_000_000f, live ? "" : " remembered", all.size(), trackArray.length));
        return new Definition(groups.get(best[0]), trackArray, C.SELECTION_REASON_INITIAL,
                new CarTrackSelection.Pick(f, sGeneration));
    }

    /** For the player UI: the one version playing, out of a pick's definition. */
    public static Definition single(Definition definition) {
        if (definition.data instanceof CarTrackSelection.Pick) {
            Format f = ((CarTrackSelection.Pick) definition.data).format;
            int index = definition.group.indexOf(f);
            if (index >= 0) {
                return new Definition(definition.group, index);
            }
        }
        return definition;
    }

    static void onSelectionCreated(CarTrackSelection selection) {
        sLive = new WeakReference<>(selection);
    }

    /** The next selection rebuilds the video stream even if it picks a version in the current selection. */
    public static synchronized void forcePick() {
        sGeneration++;
    }

    /**
     * Play this version from the next piece of video on, without stopping. False when it isn't in the selection
     * playing now (another codec, or a video started before the selection existed): re-select then.
     */
    private static boolean switchTo(Format f) {
        CarTrackSelection live = sLive.get();
        if (live == null || !live.select(f)) {
            return false;
        }
        sVideo = f;
        return true;
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

    /**
     * Frames dropped at this level: don't use it (this codec, this much picture per second) for a week. Returns true
     * when a lighter version took over without a stop; false when the selection must be redone.
     */
    public static synchronized boolean onFramesDropping(@Nullable Format f) {
        if (f == null || sPrefs == null) {
            return false;
        }
        String family = family(f);
        float load = load(f);
        float old = sPrefs.getFloat(KEY_BAD + family, Float.MAX_VALUE);
        sPrefs.edit()
                .putFloat(KEY_BAD + family, Math.min(old, load))
                .putLong(KEY_BAD_TIME + family, System.currentTimeMillis())
                .apply();
        CarPerf.log("frames dropping at " + describe(f) + ": use lighter " + family + " from now on");
        // The data arrives fine - change at the next piece of video rather than rebuild the stream
        Format best = null;
        for (Format c : sCandidates) {
            if (c.bitrate > 0 && c.bitrate <= sVideoBitrateCap && !isTooHeavy(c) && load(c) < load
                    && (best == null || better(c, best))) {
                best = c;
            }
        }
        if (best != null && switchTo(best)) {
            CarPerf.log("lighter picture from the next piece on: " + describe(best));
            return true;
        }
        return false;
    }

    /**
     * Playback keeps stalling (or never starts) although data is arriving: below this bit rate for the rest of the
     * video - lower still when the network measured less - and start lower next time. Returns true when the picture
     * will change (re-select then); false when it's already the lightest version.
     */
    public static synchronized boolean onStalls(@Nullable Format f, long meterEstimate) {
        if (f == null) {
            f = sVideo;
        }
        if (f == null || f.bitrate <= 0) {
            return false;
        }
        long oneDown = (long) (f.bitrate * 0.85f); // a version that saves less than 15% is no step down (144p VP9 -> 144p AVC)
        long cap = oneDown;
        if (meterEstimate > 0) {
            cap = Math.min(cap, (long) (meterEstimate * BANDWIDTH_SHARE));
        }
        if (!hasLighter(cap)) {
            // nothing fits the measured speed; at least one step down, if there is one
            cap = oneDown;
        }
        if (!hasLighter(cap)) {
            boolean changed = !sWeak;
            sWeak = true;
            sVideoBitrateCap = f.bitrate; // stay here
            saveEstimate((long) (f.bitrate / BANDWIDTH_SHARE));
            if (changed) {
                CarPerf.log("stalls at " + describe(f) + ": already the lightest version, lighter sound");
            }
            return changed; // one re-selection so the light sound applies; the picture stays the same stream
        }
        sVideoBitrateCap = (int) Math.max(cap, 1);
        saveEstimate((long) (Math.max(cap, 1) / BANDWIDTH_SHARE));
        CarPerf.log(String.format(Locale.US, "stalls at %s: lower for this video (under %.2f Mbps)",
                describe(f), sVideoBitrateCap / 1_000_000f));
        return true;
    }

    /** A lighter version than the one playing exists (so a step down would change something). */
    public static synchronized boolean canStepDown() {
        Format f = sVideo;
        return f != null && f.bitrate > 0 && hasLighter((long) (f.bitrate * 0.85f));
    }

    public static final int UP_NONE = 0;
    public static final int UP_SWITCHED = 1;  // the better version plays from the next piece of video on
    public static final int UP_RESELECT = 2;  // re-select (the stream is rebuilt: a short re-buffer)

    /**
     * The network has had clear headroom for a while: a clearly better version (at least 1.25x the bit rate) within
     * a safe share of the measured speed takes over at the next piece of video - playback doesn't stop. Only when
     * that version isn't in the selection playing (another codec) is the selection redone, and then only for a
     * small picture (480p or less) and a big gain (1.5x): that change costs a re-buffer.
     */
    public static synchronized int liftCapForHeadroom(long meterEstimate) {
        Format now = sVideo;
        if (now == null || now.bitrate <= 0 || meterEstimate <= 0 || !isMeterWarm()) {
            return UP_NONE;
        }
        long budget = (long) (meterEstimate * 0.6f); // more margin than a fresh pick: a step back down costs a stop
        Format best = null;
        for (Format f : sCandidates) {
            if (f.bitrate > 0 && f.bitrate <= budget && !isTooHeavy(f) && (best == null || better(f, best))) {
                best = f;
            }
        }
        if (best == null || best.bitrate < now.bitrate * 1.25f || !better(best, now)) {
            return UP_NONE;
        }
        if (switchTo(best)) {
            sVideoBitrateCap = best.bitrate; // exactly this one (the next video starts from the live measurement anyway)
            CarPerf.log(String.format(Locale.US, "network ~%.1f Mbps for a while: up from %s to %s from the next piece on",
                    meterEstimate / 1_000_000f, describe(now), describe(best)));
            return UP_SWITCHED;
        }
        if (shortSide(now) > 480 || best.bitrate < now.bitrate * 1.5f) {
            return UP_NONE;
        }
        sVideoBitrateCap = best.bitrate;
        CarPerf.log(String.format(Locale.US, "network ~%.1f Mbps for a while: back up from %s to %s",
                meterEstimate / 1_000_000f, describe(now), describe(best)));
        return UP_RESELECT;
    }

    /** The video picked for this video (before its first picture appears). */
    @Nullable
    public static Format pickedVideo() {
        return sVideo;
    }

    /** A new video opens: its own limits start clean (a replay of a video that hit a tunnel shouldn't inherit them). */
    public static synchronized void onNewVideo() {
        sVideoGroups = null;
        sVideoBitrateCap = Integer.MAX_VALUE;
    }

    /** Among the versions the pick considers for this video (same decoder/size/frame-rate filters). */
    private static boolean hasLighter(long cap) {
        for (Format f : sCandidates) {
            if (f.bitrate > 0 && f.bitrate <= cap) {
                return true;
            }
        }
        return false;
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
        sMeterWarmAtMs = android.os.SystemClock.elapsedRealtime();
        if (estimate > 0) {
            saveEstimate(estimate);
        }
    }

    /** Measured recently - not a number left from a drive hours ago while the app sat in the background. */
    private static boolean isMeterWarm() {
        return sMeterWarm && android.os.SystemClock.elapsedRealtime() - sMeterWarmAtMs < ESTIMATE_FRESH_MS;
    }

    private static long savedEstimate() {
        long saved = sPrefs != null ? sPrefs.getLong(KEY_ESTIMATE, 0) : 0;
        long time = sPrefs != null ? sPrefs.getLong(KEY_ESTIMATE_TIME, 0) : 0;
        long age = System.currentTimeMillis() - time; // negative: the clock was set back (car units boot with a stale clock)
        boolean fresh = age >= 0 && age < ESTIMATE_FRESH_MS;
        return saved > 0 && fresh ? saved : FIRST_ESTIMATE;
    }

    private static void saveEstimate(long estimate) {
        if (sPrefs != null && estimate > 0) {
            sPrefs.edit().putLong(KEY_ESTIMATE, estimate).putLong(KEY_ESTIMATE_TIME, System.currentTimeMillis()).apply();
        }
    }

    // ---------------------------------------- sound ----------------------------------------

    @Nullable
    public static Definition pickAudio(TrackGroupArray groups, int[][] supports, @Nullable String language, long meterEstimate) {
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
        // Auto picture off (a fixed quality): the video pick didn't run this pass, so judge the network here
        final boolean weak = sWeakFromVideo ? sWeak
                : meterEstimate > 0 && isMeterWarm() && meterEstimate * BANDWIDTH_SHARE < WEAK_BUDGET;
        sWeakFromVideo = false;
        if (weak) { // weak signal: a light version (~50-70 kbps sounds fine in a moving car), the picture needs the bandwidth
            List<int[]> light = filter(c, groups, f -> f.bitrate > 0 && f.bitrate <= 80_000);
            if (!light.isEmpty()) {
                c = light;
            }
        }
        for (int[] gt : c) {
            if (best == null || audioScore(format(groups, gt)) > audioScore(format(groups, best))) {
                best = gt;
            }
        }

        Format f = format(groups, best);
        sAudio = f;
        CarPerf.log("auto sound: " + describeAudio(f) + (weak ? " (weak signal)" : "") + " (" + all.size() + " versions)");
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

    private static int maxShortSide(List<int[]> list, TrackGroupArray groups) {
        int max = 0;
        for (int[] gt : list) {
            max = Math.max(max, shortSide(format(groups, gt)));
        }
        return max;
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
