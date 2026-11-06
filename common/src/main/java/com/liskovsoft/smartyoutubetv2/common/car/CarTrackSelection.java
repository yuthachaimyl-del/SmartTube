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

    private final int mGeneration;
    private volatile int mPending;
    private volatile int mSelected;
    private volatile int mReason = C.SELECTION_REASON_INITIAL;

    private CarTrackSelection(TrackGroup group, int[] tracks, Pick pick) {
        super(group, tracks);
        mGeneration = pick.generation;
        int index = indexOfFormat(pick.format);
        mSelected = index >= 0 ? index : length - 1; // (the lightest, should the pick be missing)
        mPending = mSelected;
    }

    /** Play this version from the next piece of video on. False when it isn't in this selection. */
    boolean select(Format format) {
        int index = indexOfFormat(format);
        if (index < 0) {
            return false;
        }
        mPending = index;
        mReason = C.SELECTION_REASON_ADAPTIVE;
        return true;
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
