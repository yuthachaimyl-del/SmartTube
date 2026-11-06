package com.liskovsoft.smartyoutubetv2.common.car;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.AdaptiveQuality;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Car build: watches a playing video for the kinds of stutter and lets auto picture step down (and, later, back up):
 * <ul>
 * <li>Dropped frames - the decoder can't keep up with this resolution/frame rate. The level is remembered as too
 * heavy, so next videos start lighter.</li>
 * <li>Repeated or long stalls, or no picture at all after a few seconds - the network can't carry this bit rate.
 * Lower, again if needed, while data is still arriving (a tunnel isn't fixed by going lower).</li>
 * <li>Clear headroom for a while after that - back up, so a long video doesn't stay small after one weak patch. The
 * better version takes over at the next piece of video; playback doesn't stop.</li>
 * </ul>
 * The first seconds after a start, a seek, a quality change or a change of the video's size on screen don't count.
 */
public final class CarPlaybackMonitor implements Player.EventListener, VideoListener {
    private static final long TICK_MS = 2_000;
    private static final long WINDOW_MS = 8_000;
    private static final long GRACE_MS = 4_000;
    private static final long WARM_AFTER_MS = 20_000;   // played this long: the network speed is measured
    private static final long SAVE_EVERY_MS = 60_000;
    private static final long LONG_STALL_MS = 5_000;
    private static final long STALLS_WITHIN_MS = 90_000;
    private static final long SLOW_START_MS = 8_000;    // no picture after this long: the version is too heavy for here
    private static final long STEP_AGAIN_MS = 15_000;   // a weak area: step down again if it still stalls
    private static final long DATA_RECENT_MS = 15_000;  // data arrived this recently: a weak signal, not a dead one
    private static final long UP_FIRST_WAIT_MS = 30_000;    // steady for this long after a step before going back up
    private static final long UP_MAX_WAIT_MS = 4 * 60_000;  // (doubles after each step up: a step back down would cost a stop)
    private static final long UP_QUIET_MS = 60_000;         // and no stall for this long
    private static final long UP_MIN_BUFFER_MS = 10_000;    // and this much already loaded ahead
    private static CarPlaybackMonitor sCurrent;
    private final WeakReference<SimpleExoPlayer> mPlayer;
    private final WeakReference<RestoreTrackSelector> mSelector;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
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
    private long mSinceSaveMs;
    private long mSteppedForStallsMs = -1;
    private long mStartWaitMs;
    private long mLastBufferedPosMs = -1;
    private long mLastProgressMs;   // elapsedRealtime when loaded data last grew
    private long mLastStepMs;       // last change of level (down or up)
    private long mUpWaitMs = UP_FIRST_WAIT_MS;
    private boolean mSaidNoData;
    @Nullable private TrackGroupArray mGroups;
    @Nullable private Format mShownFormat; // the version the decoder is on (logged when it changes)
    private long mHeadroomLogMs;

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
        boolean normalSpeed = Math.abs(player.getPlaybackParameters().speed - 1f) < 0.01f;

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
            mSinceSaveMs += TICK_MS;
            if (mPlayedMs >= WARM_AFTER_MS && mSinceSaveMs >= SAVE_EVERY_MS) {
                saveEstimate();
            } else if (mPlayedMs == WARM_AFTER_MS) {
                saveEstimate();
            }
        }

        Format shown = player.getVideoFormat();
        if (shown != null && shown != mShownFormat && !shown.equals(mShownFormat)) {
            mShownFormat = shown;
            CarPerf.log("picture now " + CarAutoQuality.describeFormat(shown) + " at " + player.getCurrentPosition() / 1000 + " s");
        }

        // Is data arriving? Loaded data grows while a chunk downloads (slowly on a weak signal, not at all in a tunnel)
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

        checkLongStall(player, now);
        checkSlowStart(player, now);
        checkHeadroom(player, now, playing && normalSpeed);

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
        if (ratio >= 0.25f) {
            mStrikes = 2;
        } else if (ratio >= 0.06f && droppedInWindow >= 6) {
            mStrikes++;
        } else {
            mStrikes = 0;
        }
        if (ratio >= 0.02f) {
            CarPerf.log(String.format(Locale.US, "dropped frames %.0f%% (%d of %d)", ratio * 100, droppedInWindow, frames));
        }
        if (mStrikes >= 2) {
            mStrikes = 0;
            Format format = player.getVideoFormat();
            if (CarAutoQuality.onFramesDropping(format)) {
                mLastStepMs = now; // the lighter version takes over at the next piece of video
                grace();
            } else {
                stepDown(now);
            }
        }
    }

    private void checkLongStall(SimpleExoPlayer player, long now) {
        if (mBufferingSinceMs > 0 && player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING
                && now - mBufferingSinceMs >= LONG_STALL_MS && canStepForStalls(now)) {
            onStalls(player, now);
        }
    }

    /**
     * The video doesn't start: the picked version is too heavy for the signal here (a fast road's measurement, a weak
     * area now). Go lighter every few seconds until it plays - the YouTube app does the same by starting low.
     */
    private void checkSlowStart(SimpleExoPlayer player, long now) {
        boolean waiting = !mWasReady && mGroups != null && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING;
        if (!waiting) {
            mStartWaitMs = 0;
            return;
        }
        mStartWaitMs += TICK_MS;
        if (mStartWaitMs < SLOW_START_MS || !AdaptiveQuality.isEnabled()) {
            return;
        }
        mStartWaitMs = 0;
        if (!dataArriving(now)) {
            sayNoData("no picture after " + (SLOW_START_MS / 1000) + " s");
            return;
        }
        RestoreTrackSelector selector = mSelector.get();
        long meter = selector != null && player.getTotalBufferedDuration() > 0 ? selector.getBitrateEstimate() : -1;
        CarPerf.log("no picture after " + (SLOW_START_MS / 1000) + " s");
        if (CarAutoQuality.onStalls(CarAutoQuality.pickedVideo(), meter)) {
            stepDown(now);
        }
    }

    private boolean canStepForStalls(long now) {
        return mSteppedForStallsMs < 0 || now - mSteppedForStallsMs >= STEP_AGAIN_MS;
    }

    /** Loaded data grew recently: the signal is weak, not gone. */
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

    private void onStalls(SimpleExoPlayer player, long now) {
        if (!AdaptiveQuality.isEnabled()) {
            return;
        }
        if (!dataArriving(now)) {
            sayNoData("stalled");
            return;
        }
        // Lower, and again if it keeps stalling (a weak area): down to the lightest version, then it just waits
        mSteppedForStallsMs = now;
        RestoreTrackSelector selector = mSelector.get();
        long meter = selector != null && mPlayedMs >= 6_000 ? selector.getBitrateEstimate() : -1;
        if (CarAutoQuality.onStalls(player.getVideoFormat(), meter)) {
            stepDown(now);
        }
    }

    /**
     * Playing smoothly with plenty loaded ahead, and the measured speed would carry a clearly better picture: it takes
     * over at the next piece of video (a step back down would cost a stop, so wait longer between each step up).
     */
    private void checkHeadroom(SimpleExoPlayer player, long now, boolean playingNormally) {
        if (!playingNormally || !AdaptiveQuality.isEnabled() || mPlayedMs < WARM_AFTER_MS || now < mGraceUntilMs
                || (mLastStepMs > 0 && now - mLastStepMs < mUpWaitMs)
                || (mLastStallMs > 0 && now - mLastStallMs < UP_QUIET_MS)
                || player.getTotalBufferedDuration() < UP_MIN_BUFFER_MS) {
            if (playingNormally && mPlayedMs >= WARM_AFTER_MS && now - mHeadroomLogMs > 60_000) {
                mHeadroomLogMs = now;
                CarPerf.log(String.format(Locale.US, "headroom wait: grace %d s, step %d s ago (wait %d s), stall %d s ago, %d s loaded",
                        Math.max(0, mGraceUntilMs - now) / 1000, mLastStepMs > 0 ? (now - mLastStepMs) / 1000 : -1, mUpWaitMs / 1000,
                        mLastStallMs > 0 ? (now - mLastStallMs) / 1000 : -1, player.getTotalBufferedDuration() / 1000));
            }
            return;
        }
        RestoreTrackSelector selector = mSelector.get();
        if (selector == null) {
            return;
        }
        int up = CarAutoQuality.liftCapForHeadroom(selector.getBitrateEstimate());
        if (up != CarAutoQuality.UP_NONE) {
            mUpWaitMs = Math.min(mUpWaitMs * 2, UP_MAX_WAIT_MS);
            mLastStepMs = now;
            if (up == CarAutoQuality.UP_RESELECT) {
                CarAutoQuality.forcePick();
                selector.reselect();
            }
            grace();
        }
    }

    /** Stalled or not starting: rebuild the stream with the lighter version now (don't wait for the slow download). */
    private void stepDown(long now) {
        mLastStepMs = now;
        mSaidNoData = false;
        RestoreTrackSelector selector = mSelector.get();
        if (selector != null) {
            CarAutoQuality.forcePick();
            selector.reselect();
        }
        grace();
    }

    private void saveEstimate() {
        mSinceSaveMs = 0;
        RestoreTrackSelector selector = mSelector.get();
        SimpleExoPlayer player = mPlayer.get();
        if (selector != null && mPlayedMs >= WARM_AFTER_MS) {
            long estimate = selector.getBitrateEstimate();
            CarAutoQuality.onMeterWarm(estimate);
            CarPerf.log(String.format(Locale.US, "network measured ~%.2f Mbps (%d s loaded ahead)", estimate / 1_000_000f,
                    player != null ? player.getTotalBufferedDuration() / 1000 : -1));
        }
    }

    // ---------------------------------------- player events ----------------------------------------

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        long now = SystemClock.elapsedRealtime();
        if (playbackState == Player.STATE_READY) {
            if (mBufferingSinceMs > 0 && playWhenReady) {
                long stallMs = now - mBufferingSinceMs;
                boolean repeated = mLastStallMs > 0 && now - mLastStallMs < STALLS_WITHIN_MS;
                mLastStallMs = now;
                CarPerf.log("stall " + stallMs + " ms" + (repeated ? " (again)" : ""));
                SimpleExoPlayer player = mPlayer.get();
                if (player != null && repeated && canStepForStalls(now)) {
                    onStalls(player, now);
                }
            }
            mBufferingSinceMs = -1;
            mWasReady = true;
            grace(); // frames right after buffering don't count
        } else if (playbackState == Player.STATE_BUFFERING) {
            // A stall: buffering while playing, not right after a start or a seek
            boolean afterSeek = now < mGraceUntilMs;
            mBufferingSinceMs = mWasReady && playWhenReady && !afterSeek ? now : -1;
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
        }
    }

    @Override
    public void onSeekProcessed() {
        grace();
        mBufferingSinceMs = -1;
        mLastBufferedPosMs = -1;
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        if (trackGroups != null && !trackGroups.equals(mGroups)) { // another video
            mGroups = trackGroups;
            mWasReady = false;
            mBufferingSinceMs = -1; // a stall of the last video isn't this one's
            mLastStallMs = -1;
            mSteppedForStallsMs = -1;
            mStartWaitMs = 0;
            mStrikes = 0;
            mLastBufferedPosMs = -1;
            mLastProgressMs = 0;
            mLastStepMs = 0;
            mUpWaitMs = UP_FIRST_WAIT_MS;
            mSaidNoData = false;
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
