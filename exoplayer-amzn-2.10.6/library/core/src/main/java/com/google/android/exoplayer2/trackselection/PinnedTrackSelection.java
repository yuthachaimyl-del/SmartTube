package com.google.android.exoplayer2.trackselection;

import com.google.android.exoplayer2.Format;

/**
 * Car build: a {@link TrackSelection} whose selected track is chosen by the app (not by the bandwidth meter). The other
 * tracks are only there so the app can switch to one of them in the middle of the stream without rebuilding it; the
 * chunk sources treat such a selection like a single track otherwise (no track blacklisting on a load error).
 */
public interface PinnedTrackSelection {
    /**
     * The app has switched to a lighter track while a chunk of {@code loadingFormat} is still loading. How long may
     * the rest of that chunk still take (microseconds) before it is better given up, so the lighter track can be
     * loaded in its place (see {@code CancellableChunkSource})? {@code C.TIME_UNSET}: keep loading it whatever it
     * takes (also when {@code loadingFormat} is the track switched to).
     */
    long getAbandonBudgetUs(Format loadingFormat);

    /** The track the app has switched to; the chunk source takes it up with the next chunk it makes. */
    Format getPendingFormat();
}
