package com.liskovsoft.smartyoutubetv2.common.car;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.LiveBandwidthMeter;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.AdaptiveQuality;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Car build: watches a playing video once a second and keeps auto picture where it plays smoothly.
 * <p>
 * The network (the speed is measured while the pieces of video come in, see {@link LiveBandwidthMeter}):
 * <ul>
 * <li>It no longer carries the version that is playing and the loaded video is running out: down, straight to the
 * version that fits the measured speed, from the next piece of video on. What is already loaded keeps playing, so
 * nothing stops; a piece of the old version that is still coming in too slowly is given up.</li>
 * <li>It carries a clearly better version and enough is loaded: up, within seconds - the pieces loaded beyond the next
 * few seconds are fetched again in the better version. An up that had to be taken back makes the next one wait longer.</li>
 * <li>Playback has already stalled (or doesn't start): the video stream is restarted right away at the version that
 * fits - and with the light sound version when even the lightest picture doesn't fit. When nothing arrives at all
 * (a tunnel) nothing is changed: going lower doesn't help there.</li>
 * </ul>
 * The decoder: dropped frames mean it can't keep up with this resolution/frame rate. A lighter version takes over and
 * the level is remembered as too heavy, so next videos start lighter.
 * <p>
 * The first seconds after a start, a seek, a quality change or a change of the video's size on screen don't count.
 */
public final class CarPlaybackMonitor implements Player.EventListener, VideoListener {
    private static final long TICK_MS = 1_000;
    // The decoder
    private static final long WINDOW_MS = 8_000;
    private static final long GRACE_MS = 4_000;
    private static final long DECODER_SETTLE_MS = 10_000;   // a video's first seconds say little about the decoder
    // The network
    private static final float UP_SHARE = 0.8f;             // a better version must fit in this share of the measured speed
    private static final float DOWN_SHARE = 0.75f;          // going down: to the version that fits in this share
    private static final long UP_MIN_BUFFER_MS = 10_000;    // this much loaded ahead before going up
    private static final long UP_MIN_BUFFER_FAST_MS = 5_000; // (this much when the network carries the better one twice over)
    private static final int UP_STREAK = 3;                 // the better version must fit this many seconds in a row
    private static final long UP_AFTER_UP_MS = 6_000;
    private static final long UP_AFTER_DOWN_MS = 20_000;    // (times the penalty, when ups keep being taken back)
    private static final long UP_AFTER_STALL_MS = 20_000;
    private static final long UP_REGRET_MS = 40_000;        // down again this soon after an up: that up was too eager
    private static final int MAX_PENALTY = 6;
    private static final long PENALTY_FORGET_MS = 3 * 60_000;
    private static final long DOWN_BUFFER_MS = 25_000;      // with more than this loaded ahead a slow patch is ridden out
    private static final long COMFORT_BUFFER_MS = 12_000;   // with less than this only the speed of the last seconds counts
    private static final long URGENT_BUFFER_MS = 8_000;     // with less than this: down now, whatever the trend
    private static final long SAFETY_BUFFER_MS = 4_000;     // a slow piece may run what's loaded down to this, no further
    private static final long LAST_RESORT_BUFFER_MS = 6_000;
    private static final long DOWN_AGAIN_MS = 5_000;
    private static final long TREND_MS = 4_000;
    private static final long STALL_REACT_MS = 2_000;       // stalled this long: restart at what the network carries
    private static final long STALL_REACT_HEAVY_MS = 700;   // (this long when a step down was already decided)
    private static final long START_REACT_MS = 3_000;       // no picture this long after a start, though data arrives
    private static final long RESTART_AGAIN_MS = 8_000;
    private static final long START_NEEDS_MS = 1_500;       // less than this loaded: the wait is the network's
    private static final long NO_DATA_SAY_MS = 8_000;
    private static final long DATA_RECENT_MS = 15_000;      // loaded video grew this recently: a weak signal, not a dead one
    private static final long STATUS_EVERY_MS = 30_000;
    private static final long SOUND_BACK_AFTER_MS = 45_000; // the network fine for this long: the full sound version again
    private static final long SOUND_BACK_MIN_BPS = 1_500_000;
    private static final long SOUND_BACK_MIN_BUFFER_MS = 15_000;
    private static final long SOUND_CHANGE_AGAIN_MS = 30_000;
    /** A line a second about the network and the buffer: adb shell setprop log.tag.CarNet DEBUG (then start the app). */
    private static final String TRACE_TAG = "CarNet";
    private static final boolean TRACE = Log.isLoggable(TRACE_TAG, Log.DEBUG);
    private static final int HISTORY = 8;
    private static CarPlaybackMonitor sCurrent;
    private final WeakReference<SimpleExoPlayer> mPlayer;
    private final WeakReference<RestoreTrackSelector> mSelector;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private final boolean mEmulator = isEmulator();
    private long mGraceUntilMs;
    private int mLastDropped = -1;
    private int mLastRendered = -1;
    private int mWinDropped;
    private int mWinRendered;
    private long mWinMs;
    private int mStrikes;
    private boolean mWasReady;
    private long mBufferingSinceMs = -1;
    private long mLastStallMs = -1;
    private long mPlayedMs;
    private long mSinceStatusMs;
    private long mStartWaitMs;
    private long mLastBufferedPosMs = -1;
    private long mLastProgressMs;   // elapsedRealtime when loaded video last grew
    private long mLastUpMs;
    private long mLastDownMs;
    private long mLastRestartMs;
    private int mUpStreak;
    private int mPenalty = 1;
    private long mGoodSoundSinceMs;
    private long mLastSoundChangeMs;
    private boolean mSaidNoData;
    private final long[] mHistoryMs = new long[HISTORY];     // the last seconds: when,
    private final long[] mHistoryBuffer = new long[HISTORY]; // how much was loaded ahead,
    private final boolean[] mHistoryLoading = new boolean[HISTORY]; // and whether the player was loading
    private int mHistoryNext;
    @Nullable private TrackGroupArray mGroups;
    @Nullable private Format mShownFormat; // the version the decoder is on (logged when it changes)

    private CarPlaybackMonitor(SimpleExoPlayer player, RestoreTrackSelector selector) {
        mPlayer = new WeakReference<>(player);
        mSelector = new WeakReference<>(selector);
    }

    public static void attach(SimpleExoPlayer player, RestoreTrackSelector selector) {
        detach();
        if (player == null || selector == null) {
            return;
        }
        CarPlaybackMonitor monitor = new CarPlaybackMonitor(player, selector);
        player.addListener(monitor);
        player.addVideoListener(monitor);
        monitor.grace();
        monitor.mHandler.postDelayed(monitor.mTick, TICK_MS);
        sCurrent = monitor;
    }

    public static void detach() {
        CarPlaybackMonitor monitor = sCurrent;
        sCurrent = null;
        if (monitor == null) {
            return;
        }
        monitor.mHandler.removeCallbacksAndMessages(null);
        monitor.saveEstimate();
        SimpleExoPlayer player = monitor.mPlayer.get();
        if (player != null) {
            player.removeListener(monitor);
            player.removeVideoListener(monitor);
        }
    }

    /** The video's place on screen changes (side list, second screen): a few dropped frames are expected. */
    public static void noteUiChange() {
        CarPlaybackMonitor monitor = sCurrent;
        if (monitor != null) {
            monitor.grace();
        }
    }

    private void grace() {
        mGraceUntilMs = SystemClock.elapsedRealtime() + GRACE_MS;
        resetWindow();
    }

    private void resetWindow() {
        mWinDropped = 0;
        mWinRendered = 0;
        mWinMs = 0;
    }

    private void tick() {
        SimpleExoPlayer player = mPlayer.get();
        if (player == null || sCurrent != this) {
            return;
        }
        mHandler.postDelayed(mTick, TICK_MS);

        long now = SystemClock.elapsedRealtime();
        boolean playing = player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_READY;
        float speed = player.getPlaybackParameters().speed;
        boolean normalSpeed = Math.abs(speed - 1f) < 0.01f;

        int dropped = -1;
        int rendered = -1;
        DecoderCounters counters = player.getVideoDecoderCounters();
        if (counters != null) {
            counters.ensureUpdated();
            dropped = counters.droppedBufferCount;
            rendered = counters.renderedOutputBufferCount;
        }
        int dDropped = dropped >= 0 && mLastDropped >= 0 && dropped >= mLastDropped ? dropped - mLastDropped : 0;
        int dRendered = rendered >= 0 && mLastRendered >= 0 && rendered >= mLastRendered ? rendered - mLastRendered : 0;
        mLastDropped = dropped;
        mLastRendered = rendered;

        if (playing) {
            mPlayedMs += TICK_MS;
        }

        Format shown = player.getVideoFormat();
        if (shown != null && shown != mShownFormat && !shown.equals(mShownFormat)) {
            mShownFormat = shown;
            CarPerf.log("picture now " + CarAutoQuality.describeFormat(shown) + " at " + player.getCurrentPosition() / 1000 + " s");
        }

        // Is video arriving? Loaded video grows while a piece downloads (slowly on a weak signal, not at all in a tunnel)
        long bufferedPos = player.getBufferedPosition();
        if (mLastBufferedPosMs >= 0 && bufferedPos > mLastBufferedPosMs) {
            mLastProgressMs = now;
        }
        mLastBufferedPosMs = bufferedPos;

        // Buffering after a seek (set aside while it was fresh) that goes on: a stall like any other
        if (mBufferingSinceMs < 0 && mWasReady && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING && now >= mGraceUntilMs) {
            mBufferingSinceMs = now;
        }

        long bufferedMs = player.getTotalBufferedDuration();
        boolean loading = player.isLoading();
        followNetwork(player, now, playing, bufferedMs, loading, speed);
        mHistoryMs[mHistoryNext] = now;
        mHistoryBuffer[mHistoryNext] = bufferedMs;
        mHistoryLoading[mHistoryNext] = loading;
        mHistoryNext = (mHistoryNext + 1) % HISTORY;

        mSinceStatusMs += TICK_MS;
        if (mSinceStatusMs >= STATUS_EVERY_MS && (playing || player.getPlaybackState() == Player.STATE_BUFFERING)) {
            mSinceStatusMs = 0;
            saveEstimate();
            LiveBandwidthMeter meter = LiveBandwidthMeter.getInstance();
            Format cur = CarAutoQuality.current();
            CarPerf.log(String.format(Locale.US, "network %s now / %s lately, %s, %d s loaded ahead",
                    mbps(meter.getFastEstimate()), mbps(meter.getSlowEstimate()),
                    cur != null ? CarAutoQuality.describeFormat(cur) : "-", bufferedMs / 1000));
        }

        if (!playing || !normalSpeed || now < mGraceUntilMs || !AdaptiveQuality.isEnabled()) {
            resetWindow();
            return;
        }

        mWinDropped += dDropped;
        mWinRendered += dRendered;
        mWinMs += TICK_MS;
        if (mWinMs < WINDOW_MS) {
            return;
        }

        int droppedInWindow = mWinDropped;
        int frames = mWinDropped + mWinRendered;
        float ratio = frames > 0 ? mWinDropped / (float) frames : 0;
        resetWindow();
        if (frames < 60) {
            return;
        }
        if (ratio >= 0.02f) {
            CarPerf.log(String.format(Locale.US, "dropped frames %.0f%% (%d of %d)", ratio * 100, droppedInWindow, frames));
        }
        if (mEmulator || mPlayedMs < DECODER_SETTLE_MS) {
            mStrikes = 0; // an emulator's software decoder says nothing about a format; neither do a video's first seconds
            return;
        }
        // Two bad windows in a row: one can be something else going on in the unit for a moment
        if (ratio >= 0.06f && droppedInWindow >= 6) {
            mStrikes++;
        } else {
            mStrikes = 0;
        }
        if (mStrikes >= 2) {
            mStrikes = 0;
            if (CarAutoQuality.onFramesDropping(player.getVideoFormat())) {
                mLastDownMs = now; // the lighter version takes over at the next piece of video
                grace();
            }
        }
    }

    // ---------------------------------------- the network ----------------------------------------

    private void followNetwork(SimpleExoPlayer player, long now, boolean playing, long bufferedMs, boolean loading, float speed) {
        Format cur = CarAutoQuality.current();
        if (!AdaptiveQuality.isEnabled() || mGroups == null || cur == null || cur.bitrate <= 0) {
            mStartWaitMs = 0;
            return;
        }
        LiveBandwidthMeter meter = LiveBandwidthMeter.getInstance();
        long fast = meter.getFastEstimate();
        long slow = meter.getSlowEstimate();
        long safe = fast < 0 ? slow : slow < 0 ? fast : Math.min(fast, slow);
        float rate = Math.max(1f, speed); // faster playback needs the data faster
        // The piece of video that is coming in can still be a heavier version than the one selected (a step down takes
        // effect with the next piece)
        Format inFlight = CarAutoQuality.loading();
        boolean heavyInFlight = loading && inFlight != null && inFlight.bitrate > cur.bitrate && !inFlight.equals(cur);
        Format carried = heavyInFlight ? inFlight : cur; // what the network has to carry right now
        long need = (long) (CarAutoQuality.need(carried) * rate);
        boolean wants = player.getPlayWhenReady();
        boolean buffering = player.getPlaybackState() == Player.STATE_BUFFERING;
        boolean live = player.isCurrentWindowDynamic();

        if (TRACE) {
            Log.d(TRACE_TAG, String.format(Locale.US, "net %s/%s buf %.1fs %s%s%s at %s%s",
                    mbps(fast), mbps(slow), bufferedMs / 1000f, playing ? "playing" : buffering ? "buffering" : "idle",
                    loading ? " loading" : "", meter.isTransferring() ? " transfer" : "", CarAutoQuality.describeFormat(cur),
                    heavyInFlight ? " (coming in: " + CarAutoQuality.describeFormat(inFlight) + ")" : ""));
        }

        if (mPenalty > 1 && mLastDownMs > 0 && now - mLastDownMs > PENALTY_FORGET_MS) {
            mPenalty = 1;
        }

        // 1. No picture yet
        if (!mWasReady && wants && buffering) {
            mStartWaitMs += TICK_MS;
            if (bufferedMs >= START_NEEDS_MS) {
                return; // enough is loaded: the player is getting ready (decoder, first frame), not waiting for data
            }
            if (fast <= 0) { // nothing known, or nothing arriving at all: a lighter version wouldn't arrive either
                if (mStartWaitMs >= NO_DATA_SAY_MS && !dataArriving(now)) {
                    sayNoData("no picture after " + (mStartWaitMs / 1000) + " s");
                }
                return;
            }
            if (mStartWaitMs >= START_REACT_MS && need > fast * 0.9f && canRestart(now)) {
                Format target = fit(fast, rate);
                boolean lightSound = needsLightSound(fast, rate);
                if (CarAutoQuality.isLighter(target, cur) || lightSound) {
                    restart(now, target != null ? target : cur, fast, lightSound, String.format(Locale.US,
                            "no picture after %d s, network %s", mStartWaitMs / 1000, mbps(fast)));
                }
            }
            return;
        }
        mStartWaitMs = 0;

        // 2. Stalled in the middle
        if (mWasReady && wants && buffering) {
            long stalledMs = mBufferingSinceMs > 0 ? now - mBufferingSinceMs : -1;
            // (a heavier piece than the version selected is still coming in and playback has caught up with it: no
            // point in waiting to see how it goes)
            if (stalledMs < (heavyInFlight ? STALL_REACT_HEAVY_MS : STALL_REACT_MS) || !canRestart(now)) {
                return;
            }
            if (fast <= 0) {
                if (!dataArriving(now)) {
                    sayNoData("stalled");
                }
                return;
            }
            if (need > fast * 0.9f) {
                Format target = fit(fast, rate);
                if (heavyInFlight && (target == null || target.bitrate > cur.bitrate)) {
                    target = cur; // not above what was already decided
                }
                boolean lightSound = needsLightSound(fast, rate);
                if (CarAutoQuality.isLighter(target, carried) || lightSound) {
                    restart(now, target != null ? target : cur, fast, lightSound, String.format(Locale.US,
                            "stalled %.1f s at %s, network %s", stalledMs / 1000f, CarAutoQuality.describeFormat(carried), mbps(fast)));
                }
            }
            return;
        }

        if (!playing) {
            mUpStreak = 0;
            mGoodSoundSinceMs = 0;
            return;
        }

        // 3. Playing. A lighter version is selected already and a heavier piece is still coming in: how long its rest may
        // take, at most, is worked out anew every second (the chunk source gives it up when it would take longer)
        if (heavyInFlight && fast > 0) {
            CarAutoQuality.setAbandonBudget(abandonBudgetUs(bufferedMs, fast, need));
        }

        // The network no longer carries what is coming in and what's loaded is running out: down in time
        if (fast > 0 && need > fast && loading && bufferedMs < DOWN_BUFFER_MS && now >= mGraceUntilMs
                && (bufferedMs < URGENT_BUFFER_MS || draining(now, bufferedMs))
                && (mLastDownMs == 0 || now - mLastDownMs >= DOWN_AGAIN_MS)) {
            mUpStreak = 0;
            // With a comfortable amount loaded a short dip counts half; with little, only what the network does right now
            long estimate = bufferedMs >= COMFORT_BUFFER_MS && slow > fast ? (fast + slow) / 2 : fast;
            Format target = fit(estimate, rate);
            if (CarAutoQuality.isLighter(target, cur)) {
                if (!live && CarAutoQuality.switchTo(target, CarTrackSelection.KEEP_ALL)) {
                    long budgetUs = abandonBudgetUs(bufferedMs, fast, need);
                    CarAutoQuality.setAbandonBudget(budgetUs);
                    CarPerf.log(String.format(Locale.US, "network %s, %d s loaded ahead: down from %s to %s from the next piece on"
                                    + " (the piece coming in may take %s)",
                            mbps(fast), bufferedMs / 1000, CarAutoQuality.describeFormat(cur), CarAutoQuality.describeFormat(target),
                            budgetUs == C.TIME_UNSET ? "its time" : (budgetUs / 1_000_000) + " s more"));
                    noteDown(now);
                } else if (bufferedMs < LAST_RESORT_BUFFER_MS && canRestart(now)) {
                    restart(now, target, fast, needsLightSound(fast, rate), String.format(Locale.US,
                            "network %s, %d s loaded ahead", mbps(fast), bufferedMs / 1000));
                }
            } else if (!heavyInFlight && bufferedMs < LAST_RESORT_BUFFER_MS && needsLightSound(fast, rate)
                    && (mLastSoundChangeMs == 0 || now - mLastSoundChangeMs >= SOUND_CHANGE_AGAIN_MS)) {
                // Already the lightest picture: the light sound version makes room (the picture stream stays as it is)
                RestoreTrackSelector selector = mSelector.get();
                if (selector != null) {
                    CarPerf.log(String.format(Locale.US, "network %s, %d s loaded ahead, already the lightest picture: the light sound version",
                            mbps(fast), bufferedMs / 1000));
                    mLastSoundChangeMs = now;
                    CarAutoQuality.useLightSound();
                    selector.reselect();
                    grace();
                }
            }
            return;
        }

        // 4. The network carries a clearly better version: up
        Format better = safe > 0 && !heavyInFlight ? CarAutoQuality.bestWithin((long) (safe * UP_SHARE / rate)) : null;
        boolean fits = better != null && CarAutoQuality.isBetter(better, cur) && better.bitrate >= cur.bitrate * 1.15f;
        if (!fits) {
            mUpStreak = 0;
        } else {
            mUpStreak++;
            long needBetter = (long) (CarAutoQuality.need(better) * rate);
            long minBuffer = safe * 0.4f >= needBetter ? UP_MIN_BUFFER_FAST_MS : UP_MIN_BUFFER_MS;
            boolean settled = now >= mGraceUntilMs
                    && (mLastUpMs == 0 || now - mLastUpMs >= UP_AFTER_UP_MS)
                    && (mLastDownMs == 0 || now - mLastDownMs >= UP_AFTER_DOWN_MS * mPenalty)
                    && (mLastStallMs <= 0 || now - mLastStallMs >= UP_AFTER_STALL_MS);
            if (mUpStreak >= UP_STREAK && settled && bufferedMs >= minBuffer) {
                // Keep enough of what's loaded to fetch the first better piece twice over; the rest is fetched again
                long keepMs = Math.max(4_000, Math.min(15_000, (long) (2 * 7_000 * (needBetter / (double) safe)) + 2_000));
                if (!live && CarAutoQuality.switchTo(better, keepMs * 1_000)) {
                    CarPerf.log(String.format(Locale.US, "network %s, %d s loaded ahead: up from %s to %s (the next %d s stay as loaded)",
                            mbps(safe), bufferedMs / 1000, CarAutoQuality.describeFormat(cur), CarAutoQuality.describeFormat(better),
                            keepMs / 1000));
                    mLastUpMs = now;
                    mUpStreak = 0;
                    grace();
                } else if (mLastRestartMs == 0 || now - mLastRestartMs >= 60_000) {
                    // Not in the stream that is playing (a live stream): only worth a restart - a short re-buffer - for a
                    // small picture and a big gain
                    if (cur.height <= 480 && better.bitrate >= cur.bitrate * 1.5f && safe * 0.6f >= needBetter) {
                        mLastUpMs = now;
                        mUpStreak = 0;
                        restart(now, better, safe, false, String.format(Locale.US, "network %s: a better picture", mbps(safe)));
                    }
                }
            }
        }

        // 5. The light sound version was taken on a weak signal and the network has been fine for a while: full sound again
        if (CarAutoQuality.isLightSound() && safe >= SOUND_BACK_MIN_BPS && bufferedMs >= SOUND_BACK_MIN_BUFFER_MS
                && (mLastStallMs <= 0 || now - mLastStallMs >= SOUND_BACK_AFTER_MS)
                && (mLastSoundChangeMs == 0 || now - mLastSoundChangeMs >= SOUND_BACK_AFTER_MS)) {
            if (mGoodSoundSinceMs == 0) {
                mGoodSoundSinceMs = now;
            } else if (now - mGoodSoundSinceMs >= SOUND_BACK_AFTER_MS) {
                mGoodSoundSinceMs = 0;
                RestoreTrackSelector selector = mSelector.get();
                if (selector != null) {
                    CarPerf.log(String.format(Locale.US, "network %s for a while: the full sound version again", mbps(safe)));
                    mLastSoundChangeMs = now;
                    CarAutoQuality.restoreFullSound();
                    selector.reselect();
                    grace();
                }
            }
        } else {
            mGoodSoundSinceMs = 0;
        }
    }

    /**
     * A heavier piece than the version selected is coming in. How long may its rest still take? Until what's loaded ahead
     * is down to a few seconds - it shrinks by the part of every second the network doesn't deliver.
     */
    private static long abandonBudgetUs(long bufferedMs, long fast, long need) {
        float delivered = need > 0 ? fast / (float) need : 1f; // seconds of video arriving per second
        if (delivered >= 0.97f) {
            return C.TIME_UNSET; // it keeps up: let it finish
        }
        long spareMs = Math.max(0, bufferedMs - SAFETY_BUFFER_MS);
        return (long) (spareMs / (1f - delivered)) * 1_000;
    }

    /** The best version the network carries at this speed, or the lightest there is. */
    @Nullable
    private static Format fit(long estimate, float rate) {
        Format f = CarAutoQuality.bestWithin((long) (estimate * DOWN_SHARE / rate));
        return f != null ? f : CarAutoQuality.lightest();
    }

    /** Even the lightest picture doesn't fit with the sound that is playing, and there's a lighter sound version. */
    private static boolean needsLightSound(long estimate, float rate) {
        Format lightest = CarAutoQuality.lightest();
        return lightest != null && CarAutoQuality.hasLighterSound() && CarAutoQuality.need(lightest) * rate > estimate * 0.9f;
    }

    /** What's loaded ahead shrank over the last seconds although the player was loading all the time. */
    private boolean draining(long now, long bufferedMs) {
        long oldest = -1;
        for (int i = 0; i < HISTORY; i++) {
            long age = now - mHistoryMs[i];
            if (mHistoryMs[i] == 0 || age > TREND_MS + TICK_MS) {
                continue;
            }
            if (!mHistoryLoading[i]) {
                return false;
            }
            if (age >= TREND_MS - TICK_MS / 2) {
                oldest = Math.max(oldest, mHistoryBuffer[i]);
            }
        }
        return oldest >= 0 && bufferedMs + 1_000 < oldest;
    }

    private boolean canRestart(long now) {
        return mLastRestartMs == 0 || now - mLastRestartMs >= RESTART_AGAIN_MS;
    }

    private void noteDown(long now) {
        if (mLastUpMs > 0 && now - mLastUpMs < UP_REGRET_MS) {
            mPenalty = Math.min(mPenalty * 2, MAX_PENALTY);
        }
        mLastDownMs = now;
        mUpStreak = 0;
    }

    /** Playback isn't moving: build the video stream anew at this version now (the slow download is dropped). */
    private void restart(long now, Format target, long estimate, boolean lightSound, String why) {
        RestoreTrackSelector selector = mSelector.get();
        if (selector == null) {
            return;
        }
        Format cur = CarAutoQuality.current();
        CarPerf.log(why + ": restart at " + CarAutoQuality.describeFormat(target)
                + (cur != null && !cur.equals(target) ? " (was " + CarAutoQuality.describeFormat(cur) + ")" : "")
                + (lightSound ? " with the light sound" : ""));
        mLastRestartMs = now;
        mSaidNoData = false;
        if (cur != null && CarAutoQuality.isLighter(target, cur)) {
            noteDown(now);
        }
        CarAutoQuality.restartAt(target, estimate, lightSound);
        selector.reselect();
        grace();
    }

    /** Loaded video grew recently: the signal is weak, not gone. */
    private boolean dataArriving(long now) {
        return mLastProgressMs > 0 && now - mLastProgressMs <= DATA_RECENT_MS;
    }

    private void sayNoData(String what) {
        if (!mSaidNoData) {
            mSaidNoData = true;
            CarPerf.log(what + ", but no data is arriving: not lowering (a tunnel isn't fixed by going lower)");
        }
    }

    /** Upstream's long-buffering fixer (client switch, reload, app restart) asks: are we on it? */
    public static boolean shouldDeferUpstreamFix() {
        CarPlaybackMonitor monitor = sCurrent;
        return monitor != null && AdaptiveQuality.isEnabled() && monitor.dataArriving(SystemClock.elapsedRealtime());
    }

    /** Nothing has arrived for a while (a tunnel, a dead zone, or a server that stopped answering). */
    public static boolean noDataArriving() {
        CarPlaybackMonitor monitor = sCurrent;
        return monitor != null && !monitor.dataArriving(SystemClock.elapsedRealtime());
    }

    private void saveEstimate() {
        long slow = LiveBandwidthMeter.getInstance().getSlowEstimate();
        if (slow > 0) {
            CarAutoQuality.saveEstimate(slow);
        }
    }

    private static String mbps(long bps) {
        return bps < 0 ? "?" : String.format(Locale.US, "%.2f Mbps", bps / 1_000_000f);
    }

    private static boolean isEmulator() {
        String hardware = String.valueOf(Build.HARDWARE).toLowerCase(Locale.ROOT);
        String fingerprint = String.valueOf(Build.FINGERPRINT).toLowerCase(Locale.ROOT);
        return hardware.contains("ranchu") || hardware.contains("goldfish") || fingerprint.contains("generic")
                || fingerprint.contains("emulator") || fingerprint.contains("sdk_gphone");
    }

    // ---------------------------------------- player events ----------------------------------------

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        long now = SystemClock.elapsedRealtime();
        if (playbackState == Player.STATE_READY) {
            if (mBufferingSinceMs > 0 && playWhenReady) {
                long stallMs = now - mBufferingSinceMs;
                mLastStallMs = now;
                LiveBandwidthMeter meter = LiveBandwidthMeter.getInstance();
                CarPerf.log("stall " + stallMs + " ms (network " + mbps(meter.getFastEstimate()) + ")");
            }
            mBufferingSinceMs = -1;
            mWasReady = true;
            grace(); // frames right after buffering don't count
        } else if (playbackState == Player.STATE_BUFFERING) {
            // A stall: buffering while playing, not right after a start or a seek
            boolean afterSeek = now < mGraceUntilMs;
            if (mBufferingSinceMs <= 0) {
                mBufferingSinceMs = mWasReady && playWhenReady && !afterSeek ? now : -1;
            }
        } else {
            mBufferingSinceMs = -1;
        }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            grace();
            mBufferingSinceMs = -1;
            mLastBufferedPosMs = -1; // the position jumped: compare loaded data from the next tick on
            clearHistory();
        }
    }

    @Override
    public void onSeekProcessed() {
        grace();
        mBufferingSinceMs = -1;
        mLastBufferedPosMs = -1;
        clearHistory();
    }

    private void clearHistory() {
        for (int i = 0; i < HISTORY; i++) {
            mHistoryMs[i] = 0;
        }
        mUpStreak = 0;
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        if (trackGroups != null && !trackGroups.equals(mGroups)) { // another video
            mGroups = trackGroups.length > 0 ? trackGroups : null;
            mWasReady = false;
            mBufferingSinceMs = -1; // a stall of the last video isn't this one's
            mLastStallMs = -1;
            mStartWaitMs = 0;
            mStrikes = 0;
            mPlayedMs = 0;
            mLastBufferedPosMs = -1;
            mLastProgressMs = 0;
            mLastUpMs = 0;
            mLastDownMs = 0;
            mLastRestartMs = 0;
            mPenalty = 1;
            mGoodSoundSinceMs = 0;
            mLastSoundChangeMs = 0;
            mSaidNoData = false;
            clearHistory();
        }
        grace();
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
        grace();
    }

    @Override
    public void onRenderedFirstFrame() {
        grace();
    }
}
