package com.google.android.exoplayer2.upstream;

import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * Car build: a network speed meter that is current while a download is still running.
 * <p>
 * {@link DefaultBandwidthMeter} adds a sample only when a transfer ends. One piece of video is one transfer, and on a
 * weak signal that takes tens of seconds - all that time the estimate still showed the fast road from before. This
 * meter counts the bytes as they arrive, in quarter-second slots, and divides by the time a transfer was actually open
 * (waiting between pieces with a full buffer isn't slow network), so a drop shows within a few seconds.
 * <p>
 * Two views: {@link #getFastEstimate()} over the last ~3 s of downloading (for stepping down in time) and
 * {@link #getSlowEstimate()} over the last ~12 s (for stepping up without chasing every burst).
 */
public final class LiveBandwidthMeter implements BandwidthMeter, TransferListener {
    private static final LiveBandwidthMeter INSTANCE = new LiveBandwidthMeter();
    private static final int SLOT_MS = 250;
    private static final int SLOTS = 360;               // 90 s of history
    private static final int FAST_ACTIVE_MS = 3_000;
    private static final int SLOW_ACTIVE_MS = 12_000;
    private static final int MIN_ACTIVE_MS = 750;       // less than this says nothing yet...
    private static final long MIN_BYTES = 256 * 1024;   // ...unless this much came in it (a fast network: short bursts)
    private static final long MAX_AGE_MS = 60_000;      // older measurements are about another place
    private static final long DEFAULT_ESTIMATE = 1_000_000;

    private final long[] slotNumber = new long[SLOTS];  // which slot of time this entry holds (time / SLOT_MS)
    private final long[] slotBytes = new long[SLOTS];
    private final int[] slotActiveMs = new int[SLOTS];
    private int streams;          // transfers open
    private int paused;           // of those, waiting on purpose (the server asked to come back later)
    private long accountedMs;     // time up to which open-transfer time has been counted
    private long lastByteMs;      // when data last arrived
    private long totalBytes;
    private long lastKnown = -1;  // the last slow estimate that could be computed
    private long measuredActiveMs; // the download time the last estimate computed rests on

    private LiveBandwidthMeter() {
    }

    public static LiveBandwidthMeter getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------- reading ----------------------------------------

    /** Bits per second over the last few seconds of downloading; -1 when there's nothing recent to go by. */
    public synchronized long getFastEstimate() {
        return estimate(FAST_ACTIVE_MS);
    }

    /** Bits per second over a longer stretch of downloading; -1 when there's nothing recent to go by. */
    public synchronized long getSlowEstimate() {
        long estimate = estimate(SLOW_ACTIVE_MS);
        if (estimate >= 0) {
            lastKnown = estimate;
        }
        return estimate;
    }

    /**
     * How much download time (milliseconds) the slow estimate rests on right now. Little of it means a few short bursts
     * - small pieces loaded now and then into a full buffer - which say less than a stretch of steady downloading.
     */
    public synchronized long getSlowActiveMs() {
        return estimate(SLOW_ACTIVE_MS) >= 0 ? measuredActiveMs : 0;
    }

    /** The smaller of the two views (careful: for deciding what the network carries); -1 when unknown. */
    public synchronized long getSafeEstimate() {
        long fast = estimate(FAST_ACTIVE_MS);
        long slow = getSlowEstimate();
        return fast < 0 ? slow : slow < 0 ? fast : Math.min(fast, slow);
    }

    /** A transfer is open right now (and not just waiting for the server's "come back later"). */
    public synchronized boolean isTransferring() {
        return streams - paused > 0;
    }

    /** Milliseconds since data last arrived; -1 when nothing has arrived yet. */
    public synchronized long getMsSinceLastData() {
        return lastByteMs > 0 ? SystemClock.elapsedRealtime() - lastByteMs : -1;
    }

    public synchronized long getTotalBytes() {
        return totalBytes;
    }

    /** Forget everything (a new drive, a long pause). */
    public synchronized void reset() {
        for (int i = 0; i < SLOTS; i++) {
            slotNumber[i] = 0;
            slotBytes[i] = 0;
            slotActiveMs[i] = 0;
        }
        lastKnown = -1;
    }

    private long estimate(int wantedActiveMs) {
        long now = SystemClock.elapsedRealtime();
        account(now);
        long newest = now / SLOT_MS;
        long oldest = newest - Math.min(SLOTS - 1, MAX_AGE_MS / SLOT_MS);
        long bytes = 0;
        long activeMs = 0;
        for (long n = newest; n >= oldest && activeMs < wantedActiveMs; n--) {
            int i = (int) (n % SLOTS);
            if (slotNumber[i] == n) {
                bytes += slotBytes[i];
                activeMs += slotActiveMs[i];
            }
        }
        measuredActiveMs = activeMs;
        if (activeMs < MIN_ACTIVE_MS && bytes < MIN_BYTES) {
            return -1;
        }
        return bytes * 8_000 / Math.max(activeMs, 50);
    }

    // ---------------------------------------- counting ----------------------------------------

    /** Counts the time since the last call as download time, when a transfer was open during it. */
    private void account(long now) {
        if (accountedMs == 0 || now <= accountedMs) {
            accountedMs = Math.max(accountedMs, now);
            return;
        }
        if (streams - paused > 0) {
            long from = Math.max(accountedMs, now - (long) (SLOTS - 1) * SLOT_MS);
            while (from < now) {
                long n = from / SLOT_MS;
                long slotEnd = (n + 1) * SLOT_MS;
                long to = Math.min(slotEnd, now);
                slot(n);
                slotActiveMs[(int) (n % SLOTS)] += (int) (to - from);
                from = to;
            }
        }
        accountedMs = now;
    }

    /** Makes the ring entry for this slot of time current (clears what an older slot left there). */
    private int slot(long n) {
        int i = (int) (n % SLOTS);
        if (slotNumber[i] != n) {
            slotNumber[i] = n;
            slotBytes[i] = 0;
            slotActiveMs[i] = 0;
        }
        return i;
    }

    /** The loader waits because the server said so (not the network's doing): don't count it as download time. */
    public synchronized void pause() {
        account(SystemClock.elapsedRealtime());
        paused++;
    }

    public synchronized void resume() {
        account(SystemClock.elapsedRealtime());
        if (paused > 0) {
            paused--;
        }
    }

    // ---------------------------------------- TransferListener ----------------------------------------

    @Override
    public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        // Connecting and waiting for the response to begin isn't counted (like DefaultBandwidthMeter)
    }

    @Override
    public synchronized void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        if (!isNetwork) {
            return;
        }
        account(SystemClock.elapsedRealtime());
        streams++;
    }

    @Override
    public synchronized void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
        if (!isNetwork || bytesTransferred <= 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        account(now);
        slotBytes[slot(now / SLOT_MS)] += bytesTransferred;
        totalBytes += bytesTransferred;
        lastByteMs = now;
    }

    @Override
    public synchronized void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        if (!isNetwork) {
            return;
        }
        account(SystemClock.elapsedRealtime());
        if (streams > 0) {
            streams--;
        }
        if (paused > streams) {
            paused = streams;
        }
    }

    // ---------------------------------------- BandwidthMeter ----------------------------------------

    @Override
    public synchronized long getBitrateEstimate() {
        long estimate = getSlowEstimate();
        return estimate >= 0 ? estimate : lastKnown >= 0 ? lastKnown : DEFAULT_ESTIMATE;
    }

    @Nullable
    @Override
    public TransferListener getTransferListener() {
        return this;
    }

    @Override
    public void addEventListener(Handler eventHandler, EventListener eventListener) {
        // No sample events: the estimate is read when needed
    }

    @Override
    public void removeEventListener(EventListener eventListener) {
    }
}
