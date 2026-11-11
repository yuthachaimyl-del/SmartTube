package com.google.android.exoplayer2.trackselection;

import com.google.android.exoplayer2.Format;

/**
 * Car build: a {@link TrackSelection} whose selected track is chosen by the app (not by the bandwidth meter). The other
 * tracks are only there so the app can switch to one of them in the middle of the stream without rebuilding it; the
 * chunk sources treat such a selection like a single track otherwise (no track blacklisting on a load error).
 */
public interface PinnedTrackSelection {
    /**
     * Whether a chunk of {@code loadingFormat} that is still loading should be given up, so the track the app has
     * switched to can be loaded right away (see {@code CancellableChunkSource}).
     */
    boolean shouldAbandon(Format loadingFormat);
}
