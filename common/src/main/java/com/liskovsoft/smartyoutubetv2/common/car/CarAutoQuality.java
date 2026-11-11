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
 * Picture: follows the network, like the YouTube app. A video starts with the best version this unit decodes in
 * hardware without dropping frames and the network carries right now (measured live - see
 * {@link com.google.android.exoplayer2.upstream.LiveBandwidthMeter}; a modest version when nothing is known yet). The
 * other versions of the same codec ride along in the selection ({@link CarTrackSelection}), and
 * {@link CarPlaybackMonitor} moves between them while the video plays, without stopping it: down before the loaded
 * video runs out when the network gets slower (the piece that is still coming in too slowly is given up), up again
 * within seconds once it carries more. Only when playback has already stalled is the stream restarted at the version
 * that fits the measured speed - straight there, not one step at a time. The decoder's limits are respected all the
 * way: a level that dropped frames isn't tried again for a week.
 * <p>
 * Sound: the best original stream as it is - the highest bit rate, no YouTube "stable volume" (DRC) version,
 * stereo, Opus first (48 kHz like the output: no resampling). Volume effects are off ({@link #isPureAudio}). On a
 * very weak signal a lighter version, so the picture and the sound both fit.
 */
public final class CarAutoQuality {
    private static final String PREFS = "car_auto_quality";
    private static final String KEY_ESTIMATE = "bandwidth";
    private static final String KEY_ESTIMATE_TIME = "bandwidth_time";
    private static final long ESTIMATE_FRESH_MS = 15 * 60_000; // an older measurement says little about where the car is now
    private static final String KEY_BAD = "too_heavy_";
    private static final String KEY_BAD_TIME = "too_heavy_time_";
    private static final long REMEMBERED_MAX = 3_000_000; // a remembered speed starts a video at 720p at most
    private static final long REMEMBER_MS = 7L * 24 * 60 * 60_000; // try the heavier level again after a week
    private static final int MAX_SHORT_SIDE = 1080; // car screens: 1920x720 and 2400x896
    /** Nothing measured yet: start around 360p - it shows at once on any usable signal, and goes up within seconds. */
    private static final long START_ESTIMATE = 1_200_000;
    private static final float SHARE_LIVE = 0.8f;        // of a speed measured just now
    private static final float SHARE_REMEMBERED = 0.7f;  // of a speed from a few minutes ago
    /** Below this budget the network is weak: lighter sound too, so the picture gets the bandwidth. */
    private static final long WEAK_BUDGET = 800_000;
    private static final int DEFAULT_AUDIO_BPS = 130_000;
    private static volatile boolean sWeak;
    private static SharedPreferences sPrefs;
    /** The video pick of this selection pass decided {@link #sWeak} (false: auto picture is off, decide from the meter). */
    private static volatile boolean sWeakFromVideo;
    @Nullable private static TrackGroupArray sVideoGroups; // the video the candidates belong to
    /** The versions of the current video that pass every filter but the network one (same order as the groups). */
    private static final List<Format> sCandidates = new ArrayList<>();
    @Nullable private static volatile Format sVideo;
    @Nullable private static volatile Format sAudio;
    private static volatile boolean sLighterAudio; // a lighter sound version than the one playing exists
    private static boolean sKeepWeak;              // the next pick leaves sWeak as it was set (restoreFullSound)
    /** Bumped when the next selection must rebuild the video stream (a stall: drop the slow download at once). */
    private static int sGeneration;
    /** The version the next selection must take (a restart after a stall), the speed it was chosen for. */
    @Nullable private static Format sRestartTarget;
    private static long sRestartEstimate = -1;
    private static boolean sRestartLightSound;
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
    public static synchronized Definition pickVideo(TrackGroupArray groups, int[][] supports, long ignoredMeterEstimate) {
        boolean sameVideo = groups.equals(sVideoGroups); // (a re-selection gets a new array with the same tracks)
        sVideoGroups = groups;

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

        // The network: measured live in this session, else what the last minutes of the last drive measured, else a
        // modest start. (While a video plays the monitor follows the network; this is only where it starts.)
        Format restart = sRestartTarget;
        long restartEstimate = sRestartEstimate;
        boolean lightSound = sRestartLightSound;
        sRestartTarget = null;
        sRestartEstimate = -1;
        sRestartLightSound = false;

        long live = com.google.android.exoplayer2.upstream.LiveBandwidthMeter.getInstance().getSafeEstimate();
        long estimate;
        float share;
        String source;
        if (restart != null && restartEstimate >= 0) {
            estimate = restartEstimate;
            share = SHARE_LIVE;
            source = "";
        } else if (live >= 0) {
            estimate = live;
            share = SHARE_LIVE;
            source = "";
        } else {
            long saved = savedEstimate();
            if (saved > 0) {
                estimate = Math.min(saved, REMEMBERED_MAX);
                share = SHARE_REMEMBERED;
                source = " remembered";
            } else {
                estimate = START_ESTIMATE;
                share = SHARE_LIVE;
                source = " assumed";
            }
        }
        final long budget = (long) (estimate * share);

        int[] best = null;
        String why = "";
        CarTrackSelection playing = sLive.get();
        if (restart != null) {
            best = find(c, groups, restart); // the monitor's choice after a stall
            why = " (restart)";
        } else if (sameVideo && playing != null) {
            best = find(c, groups, playing.current()); // selected again for another reason: stay where the monitor has it
            why = " (unchanged)";
        }
        if (best == null) {
            why = "";
            for (int[] gt : c) {
                Format f = format(groups, gt);
                if (need(f) <= budget && (best == null || better(f, format(groups, best)))) {
                    best = gt;
                }
            }
        }
        if (best == null) { // the network is slower than everything: the lightest one
            for (int[] gt : c) {
                if (best == null || format(groups, gt).bitrate < format(groups, best).bitrate) {
                    best = gt;
                }
            }
        }

        if (sKeepWeak || (why.equals(" (unchanged)") && restart == null)) {
            sKeepWeak = false; // selected again for another reason: the sound stays as it is too
        } else {
            sWeak = lightSound || budget < WEAK_BUDGET;
        }
        sWeakFromVideo = true;

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
        CarPerf.log(String.format(Locale.US, "auto picture: %s%s (network ~%.1f Mbps%s, %d versions, %d switchable)",
                describe(f), why, estimate / 1_000_000f, source, all.size(), trackArray.length));
        return new Definition(groups.get(best[0]), trackArray, C.SELECTION_REASON_INITIAL,
                new CarTrackSelection.Pick(f, sGeneration));
    }

    @Nullable
    private static int[] find(List<int[]> list, TrackGroupArray groups, @Nullable Format wanted) {
        if (wanted == null) {
            return null;
        }
        for (int[] gt : list) {
            Format f = format(groups, gt);
            if (f == wanted || f.equals(wanted)) {
                return gt;
            }
        }
        return null;
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

    // ---------------------------------------- following the network (for CarPlaybackMonitor) ----------------------------------------

    /** The version auto picture is on (playing, or taking over at the next piece of video). */
    @Nullable
    public static Format current() {
        return sVideo;
    }

    /** What the network has to carry for this version: its own bit rate and the sound's. */
    public static long need(@Nullable Format video) {
        if (video == null || video.bitrate <= 0) {
            return 0;
        }
        Format audio = sAudio;
        return video.bitrate + (audio != null && audio.bitrate > 0 ? audio.bitrate : DEFAULT_AUDIO_BPS);
    }

    /**
     * The versions the picture can move between while this video plays: in the selection that is playing (same codec),
     * and not known to make this unit's decoder drop frames (unless that leaves nothing).
     */
    private static List<Format> ladder() {
        CarTrackSelection live = sLive.get();
        List<Format> usable = new ArrayList<>();
        List<Format> any = new ArrayList<>();
        for (Format f : sCandidates) {
            if (f.bitrate <= 0 || (live != null && !live.has(f))) {
                continue;
            }
            any.add(f);
            if (!isTooHeavy(f)) {
                usable.add(f);
            }
        }
        return usable.isEmpty() ? any : usable;
    }

    /** The best version the network carries within this budget (bits per second); null when even the lightest doesn't fit. */
    @Nullable
    public static synchronized Format bestWithin(long budget) {
        Format best = null;
        for (Format f : ladder()) {
            if (need(f) <= budget && (best == null || better(f, best))) {
                best = f;
            }
        }
        return best;
    }

    /** The next version above this one (one step up); null when it's the best there is. */
    @Nullable
    public static synchronized Format nextAbove(@Nullable Format f) {
        Format next = null;
        for (Format c : ladder()) {
            if (isBetter(c, f) && (next == null || better(next, c))) {
                next = c;
            }
        }
        return next;
    }

    /** The lightest version there is to move to. */
    @Nullable
    public static synchronized Format lightest() {
        Format best = null;
        for (Format f : ladder()) {
            if (best == null || f.bitrate < best.bitrate) {
                best = f;
            }
        }
        return best;
    }

    /**
     * The same version of the video? The format the decoder reports is the stream's own (read from the data), not the
     * object from the list of versions, so {@code equals} doesn't do: the version number (itag) decides, else the size.
     */
    public static boolean sameVersion(@Nullable Format a, @Nullable Format b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        if (a.id != null && b.id != null) {
            return a.id.equals(b.id);
        }
        return a.width == b.width && a.height == b.height && Math.round(fps(a)) == Math.round(fps(b))
                && family(a).equals(family(b));
    }

    /** {@code a} is the better picture (see {@link #better}); a null {@code b} is worse than anything. */
    public static boolean isBetter(@Nullable Format a, @Nullable Format b) {
        return a != null && (b == null || (!a.equals(b) && better(a, b)));
    }

    /** {@code a} is clearly lighter on the network than {@code b} (a version that saves less than 15% is no step down). */
    public static boolean isLighter(@Nullable Format a, @Nullable Format b) {
        return a != null && b != null && a.bitrate > 0 && a.bitrate <= b.bitrate * 0.85f;
    }

    /**
     * Play this version from the next piece of video on, without stopping (see {@link CarTrackSelection#select}).
     * False when it isn't in the selection playing now (another codec, a live stream, a video started before the
     * selection existed): restart the stream then ({@link #restartAt}).
     */
    public static synchronized boolean switchTo(Format f, long keepUs) {
        CarTrackSelection live = sLive.get();
        if (live == null || !live.select(f, keepUs)) {
            return false;
        }
        sVideo = f;
        return true;
    }

    /**
     * The version of the piece of video that was asked for last. After a {@link #switchTo} it's still the old one
     * until the piece that is loading is complete (or given up).
     */
    @Nullable
    public static Format loading() {
        CarTrackSelection live = sLive.get();
        return live != null ? live.loading() : sVideo;
    }

    /**
     * A lighter version is selected and a piece of the heavier one is still coming in: its rest may take this long at
     * most (microseconds); longer, and it's given up and loaded in the lighter version (see
     * {@link CarTrackSelection#setAbandonBudget}).
     */
    public static void setAbandonBudget(long budgetUs) {
        CarTrackSelection live = sLive.get();
        if (live != null) {
            live.setAbandonBudget(budgetUs);
        }
    }

    /**
     * The next selection takes this version and builds the video stream anew (playback has stalled: the slow download is
     * dropped at once). Re-select after this.
     *
     * @param estimate The network speed it was chosen for (bits per second, -1 unknown).
     * @param lightSound Take the light sound version too (even the lightest picture doesn't fit otherwise).
     */
    public static synchronized void restartAt(Format f, long estimate, boolean lightSound) {
        sRestartTarget = f;
        sRestartEstimate = estimate;
        sRestartLightSound = lightSound;
        sGeneration++;
    }

    /** The sound playing isn't the light version, and there is one. */
    public static boolean hasLighterSound() {
        return sLighterAudio && !sWeak;
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
     * when a lighter version takes over without a stop; false when there's nothing lighter to go to.
     */
    public static synchronized boolean onFramesDropping(@Nullable Format f) {
        if (f == null || sPrefs == null) {
            return false;
        }
        String family = family(f);
        float load = load(f);
        Format best = null;
        for (Format c : ladder()) {
            if (load(c) < load && !isTooHeavy(c) && (best == null || better(c, best))) {
                best = c;
            }
        }
        if (best == null) { // nothing lighter for the decoder: stay (marking the lightest level too heavy helps nobody)
            CarPerf.log("frames dropping at " + describe(f) + ", but there's no lighter version: staying");
            return false;
        }
        float old = sPrefs.getFloat(KEY_BAD + family, Float.MAX_VALUE);
        sPrefs.edit()
                .putFloat(KEY_BAD + family, Math.min(old, load))
                .putLong(KEY_BAD_TIME + family, System.currentTimeMillis())
                .apply();
        CarPerf.log("frames dropping at " + describe(f) + ": use lighter " + family + " from now on");
        // The data arrives fine - change at the next piece of video; the heavy pieces already loaded beyond the next
        // seconds are loaded again in the lighter version
        if (switchTo(best, 10_000_000L)) {
            CarPerf.log("lighter picture from the next piece on: " + describe(best));
            return true;
        }
        return false;
    }

    /** A new video opens: nothing carries over from the last one's selection. */
    public static synchronized void onNewVideo() {
        sVideoGroups = null;
        sRestartTarget = null;
        sRestartEstimate = -1;
        sRestartLightSound = false;
        sLive = new WeakReference<>(null);
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

    // ---------------------------------------- network memory ----------------------------------------

    /** The speed measured a few minutes ago at most (a restart of the app on the same drive); -1 when there's none. */
    private static long savedEstimate() {
        long saved = sPrefs != null ? sPrefs.getLong(KEY_ESTIMATE, 0) : 0;
        long time = sPrefs != null ? sPrefs.getLong(KEY_ESTIMATE_TIME, 0) : 0;
        long age = System.currentTimeMillis() - time; // negative: the clock was set back (car units boot with a stale clock)
        boolean fresh = age >= 0 && age < ESTIMATE_FRESH_MS;
        return saved > 0 && fresh ? saved : -1;
    }

    /** Remember the measured speed for the next start of the app. */
    public static void saveEstimate(long estimate) {
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
        long live = com.google.android.exoplayer2.upstream.LiveBandwidthMeter.getInstance().getSafeEstimate();
        final boolean weak = sWeakFromVideo ? sWeak : live >= 0 && live * SHARE_LIVE < WEAK_BUDGET;
        sWeakFromVideo = false;
        List<int[]> light = filter(c, groups, f -> f.bitrate > 0 && f.bitrate <= LIGHT_AUDIO_BPS);
        if (weak && !light.isEmpty()) { // weak signal: a light version (~50-70 kbps sounds fine in a moving car), the
            c = light;                  // picture needs the bandwidth
        }
        for (int[] gt : c) {
            if (best == null || audioScore(format(groups, gt)) > audioScore(format(groups, best))) {
                best = gt;
            }
        }

        Format f = format(groups, best);
        sAudio = f;
        sLighterAudio = !light.isEmpty() && f.bitrate > LIGHT_AUDIO_BPS;
        CarPerf.log("auto sound: " + describeAudio(f) + (weak ? " (weak signal)" : "") + " (" + all.size() + " versions)");
        return new Definition(groups.get(best[0]), best[1]);
    }

    private static final int LIGHT_AUDIO_BPS = 80_000;

    /** The light sound version is playing (picked on a weak signal). */
    public static boolean isLightSound() {
        Format f = sAudio;
        return sWeak && f != null && f.bitrate > 0 && f.bitrate <= LIGHT_AUDIO_BPS;
    }

    /**
     * The network is fine again: the next selection takes the full sound version back (re-select after this; the picture
     * stays as it is).
     */
    public static synchronized void restoreFullSound() {
        sWeak = false;
        sKeepWeak = true;
    }

    /**
     * The network doesn't carry even the lightest picture together with the full sound: the next selection takes the
     * light sound version (re-select after this; the picture stays as it is).
     */
    public static synchronized void useLightSound() {
        sWeak = true;
        sKeepWeak = true;
    }

    /** Bit rate, with Opus a bit ahead: it's 48 kHz like the output, so nothing gets resampled. */
    private static float audioScore(Format f) {
        float bitrate = f.bitrate > 0 ? f.bitrate : 64_000;
        return MimeTypes.AUDIO_OPUS.equals(f.sampleMimeType) ? bitrate * 1.15f : bitrate;
    }

    private static boolean isDrc(Format f) {
        return f.isDrc || (f.id != null && f.id.toLowerCase(Locale.ROOT).endsWith("drc"));
    }

    /**
     * Surround versions. The channel count isn't in YouTube's format list as the player gets it, so they're known by
     * their format numbers too: without that the 5.1 HE-AAC version (192 kbps) won over stereo Opus 160 on videos
     * that have surround sound, and was mixed down to stereo by the decoder.
     */
    private static boolean isMultichannel(Format f) {
        String id = f.id != null ? f.id : "";
        int dash = id.indexOf('-');
        if (dash > 0) {
            id = id.substring(0, dash);
        }
        boolean surroundItag = id.equals("256") || id.equals("258") || id.equals("325") || id.equals("327")
                || id.equals("328") || id.equals("338") || id.equals("380");
        return surroundItag || f.channelCount > 2 || MimeTypes.AUDIO_AC3.equals(f.sampleMimeType)
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

    /** e.g. "480p VP9 1.0 Mbps" */
    public static String describeFormat(Format f) {
        return describe(f);
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
