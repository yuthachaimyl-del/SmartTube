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
 * Car build: watches a playing video for the two kinds of stutter and lets auto picture step down once:
 * <ul>
 * <li>Dropped frames - the decoder can't keep up with this resolution/frame rate. The level is remembered as too
 * heavy, so next videos start lighter.</li>
 * <li>Repeated stalls - the network can't carry this bit rate. Lower for the rest of the video.</li>
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
    private boolean mSteppedForStalls;
    @Nullable private TrackGroupArray mGroups;

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

        checkLongStall(player, now);

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

        int frames = mWinDropped + mWinRendered;
        float ratio = frames > 0 ? mWinDropped / (float) frames : 0;
        resetWindow();
        if (frames < 60) {
            return;
        }
        if (ratio >= 0.25f) {
            mStrikes = 2;
        } else if (ratio >= 0.06f && mWinDropped >= 6) {
            mStrikes++;
        } else {
            mStrikes = 0;
        }
        if (ratio >= 0.02f) {
            CarPerf.log(String.format(Locale.US, "dropped frames %.0f%% (%d of %d)", ratio * 100, mWinDropped, frames));
        }
        if (mStrikes >= 2) {
            mStrikes = 0;
            Format format = player.getVideoFormat();
            CarAutoQuality.onFramesDropping(format);
            stepDown();
        }
    }

    private void checkLongStall(SimpleExoPlayer player, long now) {
        if (mBufferingSinceMs > 0 && player.getPlayWhenReady() && player.getPlaybackState() == Player.STATE_BUFFERING
                && now - mBufferingSinceMs >= LONG_STALL_MS && !mSteppedForStalls) {
            onStalls(player);
        }
    }

    private void onStalls(SimpleExoPlayer player) {
        if (!AdaptiveQuality.isEnabled()) {
            return;
        }
        mSteppedForStalls = true; // once per video: a dead zone isn't fixed by going lower and lower
        CarAutoQuality.onStalls(player.getVideoFormat());
        stepDown();
    }

    private void stepDown() {
        RestoreTrackSelector selector = mSelector.get();
        if (selector != null) {
            selector.reselect();
        }
        grace();
    }

    private void saveEstimate() {
        mSinceSaveMs = 0;
        RestoreTrackSelector selector = mSelector.get();
        if (selector != null && mPlayedMs >= WARM_AFTER_MS) {
            CarAutoQuality.onMeterWarm(selector.getBitrateEstimate());
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
                if (player != null && repeated && !mSteppedForStalls) {
                    onStalls(player);
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
        }
    }

    @Override
    public void onSeekProcessed() {
        grace();
        mBufferingSinceMs = -1;
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        if (trackGroups != null && !trackGroups.equals(mGroups)) { // another video
            mGroups = trackGroups;
            mWasReady = false;
            mLastStallMs = -1;
            mSteppedForStalls = false;
            mStrikes = 0;
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
