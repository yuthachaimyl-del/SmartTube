package com.google.android.exoplayer2.trackselection;

/**
 * Car build: a {@link TrackSelection} whose selected track is chosen by the app (not by the bandwidth meter). The other
 * tracks are only there so the app can switch to one of them in the middle of the stream without rebuilding it; the
 * chunk sources treat such a selection like a single track otherwise (no track blacklisting on a load error).
 */
public interface PinnedTrackSelection {
}
