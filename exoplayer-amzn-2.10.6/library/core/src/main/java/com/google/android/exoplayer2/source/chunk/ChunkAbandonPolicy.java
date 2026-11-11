package com.google.android.exoplayer2.source.chunk;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.upstream.LiveBandwidthMeter;

/**
 * Car build: when is a chunk that is still loading better given up, so a lighter version of the same piece of video can
 * be loaded instead (see {@link CancellableChunkSource})?
 * <p>
 * Two things must hold. The rest of the chunk takes longer, at the speed the network has right now, than the player can
 * wait (the budget the app works out from how much is loaded ahead). And giving it up pays: what is left of it is more
 * than the whole lighter replacement plus the time a new request needs to get going - a chunk that is nearly there is
 * finished, whatever its quality.
 */
public final class ChunkAbandonPolicy {
    private static final long TYPICAL_CHUNK_US = 10_000_000;

    private ChunkAbandonPolicy() {
    }

    /**
     * @param remainingBytes What is left of the chunk; -1 when unknown.
     * @param loading The format of the chunk that is loading.
     * @param bytesLoaded What has come in of it so far.
     * @param budgetUs How long the rest may still take ({@code C.TIME_UNSET}: as long as it takes).
     * @param replacement The format that would be loaded instead.
     * @param durationUs The duration of the piece of video the chunk carries ({@code C.TIME_UNSET} when unknown).
     */
    public static boolean shouldAbandon(long remainingBytes, @Nullable Format loading, long bytesLoaded, long budgetUs,
                                        @Nullable Format replacement, long durationUs) {
        if (budgetUs == C.TIME_UNSET || replacement == null || replacement.bitrate <= 0) {
            return false;
        }
        long fast = LiveBandwidthMeter.getInstance().getFastEstimate();
        if (fast <= 0) {
            return false; // nothing arrives at all (or nothing is known): a lighter version wouldn't arrive either
        }
        if (durationUs == C.TIME_UNSET || durationUs <= 0) {
            durationUs = TYPICAL_CHUNK_US;
        }
        if (remainingBytes < 0) {
            if (loading == null || loading.bitrate <= 0) {
                return false;
            }
            remainingBytes = Math.max(0, (long) (loading.bitrate / 8.0 * durationUs / 1_000_000) - bytesLoaded);
        }
        long timeLeftUs = remainingBytes * 8_000_000 / fast;
        if (timeLeftUs <= Math.max(0, budgetUs)) {
            return false; // it gets there in time
        }
        long replacementBytes = (long) (replacement.bitrate / 8.0 * durationUs / 1_000_000);
        long newRequestBytes = fast / 8; // about a second of this network goes by before a new request delivers
        return remainingBytes > replacementBytes + newRequestBytes;
    }
}
