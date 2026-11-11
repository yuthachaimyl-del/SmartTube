package com.liskovsoft.smartyoutubetv2.common.car;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.trackselection.BaseTrackSelection;
import com.google.android.exoplayer2.trackselection.PinnedTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.BandwidthMeter;

import java.util.List;

/**
 * Car build: the video selection of auto picture. One version plays (picked by {@link CarAutoQuality}); the other
 * versions of the same codec are in the selection too, so the picture can go up (or down) in the middle of the
 * stream: the change takes effect at the next piece of video, playback never stops. ExoPlayer keeps the stream as
 * long as a re-selection yields an equal selection (same tracks, same {@link Pick#generation}); a step down while the
 * video is stalled bumps the generation on purpose, so the stream is rebuilt right away (the slow download is
 * dropped) instead of waiting for the next piece.
 * <p>
 * A step up doesn't wait for the loaded data to play out either (up to a minute is loaded ahead): the pieces of the
 * old version beyond the next few seconds are dropped and loaded again in the new one, so the better picture shows
 * within seconds - still without a stop, as the next seconds are already there. A step down for the network keeps
 * everything that is loaded; the piece of the old version that is still coming in is given up when it would take
 * longer than playback can wait (see {@link #setAbandonBudget}).
 */
public final class CarTrackSelection extends BaseTrackSelection implements PinnedTrackSelection {
    /** {@link TrackSelection.Definition#data} that asks the factory for this kind of selection. */
    public static final class Pick {
        final Format format;
        final int generation;

        Pick(Format format, int generation) {
            this.format = format;
            this.generation = generation;
        }
    }

    /** Makes a {@link CarTrackSelection} for a definition carrying a {@link Pick}; anything else goes to the usual factory. */
    public static final class Factory implements TrackSelection.Factory {
        private final TrackSelection.Factory mOther;

        public Factory(TrackSelection.Factory other) {
            mOther = other;
        }

        @Override
        public TrackSelection[] createTrackSelections(Definition[] definitions, BandwidthMeter bandwidthMeter) {
            Definition[] others = new Definition[definitions.length];
            boolean anyOther = false;
            for (int i = 0; i < definitions.length; i++) {
                if (definitions[i] != null && !(definitions[i].data instanceof Pick)) {
                    others[i] = definitions[i];
                    anyOther = true;
                }
            }
            TrackSelection[] result = anyOther ? mOther.createTrackSelections(others, bandwidthMeter)
                    : new TrackSelection[definitions.length];
            for (int i = 0; i < definitions.length; i++) {
                Definition definition = definitions[i];
                if (definition != null && definition.data instanceof Pick) {
                    CarTrackSelection selection = new CarTrackSelection(definition.group, definition.tracks, (Pick) definition.data);
                    result[i] = selection;
                    CarAutoQuality.onSelectionCreated(selection);
                }
            }
            return result;
        }
    }

    /** Keep everything that is loaded (a step down for the network: what's loaded plays fine, it's what bridges the gap). */
    static final long KEEP_ALL = Long.MAX_VALUE;
    private final int mGeneration;
    private volatile int mPending;
    private volatile int mSelected;
    private volatile int mReason = C.SELECTION_REASON_INITIAL;
    /** How much loaded video of other versions stays ahead of playback; the rest is loaded again in the new version. */
    private volatile long mKeepUs = KEEP_ALL;
    /** How long the rest of a heavier piece that is still loading may take before it's given up (TIME_UNSET: any time). */
    private volatile long mAbandonBudgetUs = C.TIME_UNSET;

    private CarTrackSelection(TrackGroup group, int[] tracks, Pick pick) {
        super(group, tracks);
        mGeneration = pick.generation;
        int index = indexOfFormat(pick.format);
        mSelected = index >= 0 ? index : length - 1; // (the lightest, should the pick be missing)
        mPending = mSelected;
    }

    /**
     * Play this version from the next piece of video on. False when it isn't in this selection.
     *
     * @param keepUs How much already loaded video of other versions to keep ahead of playback ({@link #KEEP_ALL}: all
     *     of it); what lies beyond is dropped and loaded again in this version, so the change shows sooner.
     */
    boolean select(Format format, long keepUs) {
        int index = indexOfFormat(format);
        if (index < 0) {
            return false;
        }
        mAbandonBudgetUs = C.TIME_UNSET;
        mKeepUs = keepUs;
        mPending = index;
        mReason = C.SELECTION_REASON_ADAPTIVE;
        return true;
    }

    /**
     * A lighter version was selected while a piece of a heavier one is still coming in: the rest of that piece may take
     * this long at most; when it would take longer it is given up and loaded in the lighter version instead (as long as
     * the player isn't playing from it yet, and it isn't nearly complete). {@code C.TIME_UNSET}: let it finish.
     */
    void setAbandonBudget(long budgetUs) {
        mAbandonBudgetUs = budgetUs;
    }

    /** The version of the piece of video that was asked for last (loading now, or loaded). */
    Format loading() {
        return getFormat(mSelected);
    }

    /** Whether this version is one of the tracks that can be switched to. */
    boolean has(Format format) {
        return indexOfFormat(format) >= 0;
    }

    @Override
    public long getAbandonBudgetUs(Format loadingFormat) {
        long budgetUs = mAbandonBudgetUs;
        if (budgetUs == C.TIME_UNSET || loadingFormat == null) {
            return C.TIME_UNSET;
        }
        Format wanted = getFormat(mPending);
        // Only a heavier piece is ever given up (a lighter one that is loading comes in faster than its replacement)
        boolean heavier = loadingFormat != wanted && !loadingFormat.equals(wanted) && loadingFormat.bitrate > wanted.bitrate;
        return heavier ? budgetUs : C.TIME_UNSET;
    }

    @Override
    public Format getPendingFormat() {
        return getFormat(mPending);
    }

    /** The version playing (or about to). */
    Format current() {
        return getFormat(mPending);
    }

    private int indexOfFormat(@Nullable Format format) {
        if (format == null) {
            return -1;
        }
        int index = indexOf(format);
        if (index >= 0) {
            return index;
        }
        for (int i = 0; i < length; i++) { // the same version, another Format object (a re-selection)
            if (format.equals(getFormat(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void updateSelectedTrack(long playbackPositionUs, long bufferedDurationUs, long availableDurationUs,
                                    List<? extends MediaChunk> queue, MediaChunkIterator[] mediaChunkIterators) {
        mSelected = mPending; // a change applies from here (the chunk source reads the selection right after this)
    }

    @Override
    public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        long keepUs = mKeepUs;
        if (keepUs == KEEP_ALL) {
            return queue.size();
        }
        Format wanted = getFormat(mPending);
        long keepUntilUs = playbackPositionUs + keepUs;
        for (int i = 0; i < queue.size(); i++) {
            MediaChunk chunk = queue.get(i);
            if (chunk.trackFormat != wanted && chunk.startTimeUs != C.TIME_UNSET && chunk.startTimeUs > keepUntilUs) {
                return i; // from here on: loaded in the old version and not needed for a while - load it again in the new one
            }
        }
        return queue.size();
    }

    @Override
    public int getSelectedIndex() {
        return mSelected;
    }

    @Override
    public int getSelectionReason() {
        return mReason;
    }

    @Nullable
    @Override
    public Object getSelectionData() {
        return null;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return super.equals(obj) && ((CarTrackSelection) obj).mGeneration == mGeneration;
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + mGeneration;
    }
}
