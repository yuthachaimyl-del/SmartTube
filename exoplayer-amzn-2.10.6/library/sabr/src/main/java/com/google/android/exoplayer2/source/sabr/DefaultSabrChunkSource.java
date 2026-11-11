package com.google.android.exoplayer2.source.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.CheckResult;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.rawcc.RawCcExtractor;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.chunk.CancellableChunkSource;
import com.google.android.exoplayer2.source.chunk.ChunkAbandonPolicy;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.chunk.ChunkExtractorWrapper;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.ContainerMediaChunk;
import com.google.android.exoplayer2.source.chunk.InitializationChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.source.chunk.SingleSampleMediaChunk;
import com.google.android.exoplayer2.source.sabr.PlayerEmsgHandler.PlayerTrackEmsgHandler;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.RangedUri;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.parser.adapter.SabrFragmentedMp4Adapter;
import com.google.android.exoplayer2.source.sabr.parser.adapter.SabrMatroskaAdapter;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.models.AudioSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.CaptionSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.VideoSelector;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.trackselection.PinnedTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultSabrChunkSource implements SabrChunkSource, CancellableChunkSource {
    public static final class Factory implements SabrChunkSource.Factory {

        private final DataSource.Factory dataSourceFactory;
        private final int maxSegmentsPerLoad;

        public Factory(DataSource.Factory dataSourceFactory) {
            this(dataSourceFactory, 1);
        }

        public Factory(DataSource.Factory dataSourceFactory, int maxSegmentsPerLoad) {
            this.dataSourceFactory = dataSourceFactory;
            this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        }

        @Override
        public SabrChunkSource createSabrChunkSource(
                LoaderErrorThrower manifestLoaderErrorThrower,
                SabrManifest manifest,
                int periodIndex,
                int[] adaptationSetIndices,
                TrackSelection trackSelection,
                int trackType,
                long elapsedRealtimeOffsetMs,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                @Nullable PlayerTrackEmsgHandler playerEmsgHandler,
                @Nullable TransferListener transferListener) {
            DataSource dataSource = dataSourceFactory.createDataSource();
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            return new DefaultSabrChunkSource(
                    manifestLoaderErrorThrower,
                    manifest,
                    periodIndex,
                    adaptationSetIndices,
                    trackSelection,
                    trackType,
                    dataSource,
                    elapsedRealtimeOffsetMs,
                    maxSegmentsPerLoad,
                    enableEventMessageTrack,
                    closedCaptionFormats,
                    playerEmsgHandler);
        }

    }

    private static final String TAG = DefaultSabrChunkSource.class.getSimpleName();
    private static final long END_OF_STREAM_SEEK_TOLERANCE_US = 500_000L; // 0.5 seconds
    private static final long NEAR_END_TOLERANCE_US = 700_000L; // 0.7 seconds
    private final LoaderErrorThrower manifestLoaderErrorThrower;
    private final int[] adaptationSetIndices;
    private final int trackType;
    private final DataSource dataSource;
    private final long elapsedRealtimeOffsetMs;
    private final int maxSegmentsPerLoad;
    @Nullable private final PlayerTrackEmsgHandler playerTrackEmsgHandler;

    protected final RepresentationHolder[] representationHolders;

    private TrackSelection trackSelection;
    private FormatSelector formatSelector;
    private SabrManifest manifest;
    private int periodIndex;
    private IOException fatalError;
    private boolean missingLastSegment;
    private long liveEdgeTimeUs;

    @Nullable private final SabrStream sabrStream;
    private final Map<String, String> sabrHeaders;
    private int nexChunkIdx = -1;
    private long pendingSeekTimeUs = C.TIME_UNSET;
    private boolean pendingSeekMidStream; // Car build: that seek is a format change in the middle of the stream
    /** Car build: the media chunk that also carries the init segment (see canLoadInitWithMedia) */
    @Nullable private Chunk initWithMediaChunk;
    /** Car build: the format the SABR requests ask for (see {@link #maybeSwitchFormat}) */
    @Nullable private Format currentFormat;
    private int activeIndex; // its index in the selection
    /** Car build: the next media request starts the new format where the loaded data ends */
    private boolean switchPending;
    private long switchSeekTimeUs = C.TIME_UNSET;
    /**
     * Car build: the media chunks made so far (newest last) and the stream time each one's data starts at. When the
     * queue's last chunk isn't the newest one made, the chunks after it were removed (given up while loading, or
     * dropped to be loaded again in a better version): the loaded data then ends where the first removed one began.
     */
    private final ArrayList<Chunk> madeChunks = new ArrayList<>();
    private final ArrayList<Long> madeChunkStartsUs = new ArrayList<>();
    private static final int MADE_CHUNKS_KEPT = 128;
    /** A request for another format than the one before it asks for a time just inside the piece wanted. */
    private static final long SWITCH_INTO_SEGMENT_US = 50_000;
    private static volatile boolean sInitWithMediaBroken;

    /**
     * @param manifestLoaderErrorThrower Throws errors affecting loading of manifests.
     * @param manifest The initial manifest.
     * @param periodIndex The index of the period in the manifest.
     * @param adaptationSetIndices The indices of the adaptation sets in the period.
     * @param trackSelection The track selection.
     * @param trackType The type of the tracks in the selection.
     * @param dataSource A {@link DataSource} suitable for loading the media data.
     * @param elapsedRealtimeOffsetMs If known, an estimate of the instantaneous difference between
     *     server-side unix time and {@link SystemClock#elapsedRealtime()} in milliseconds, specified
     *     as the server's unix time minus the local elapsed time. If unknown, set to 0.
     * @param maxSegmentsPerLoad The maximum number of segments to combine into a single request. Note
     *     that segments will only be combined if their {@link Uri}s are the same and if their data
     *     ranges are adjacent.
     * @param enableEventMessageTrack Whether to output an event message track.
     * @param closedCaptionFormats The {@link Format Formats} of closed caption tracks to be output.
     * @param playerTrackEmsgHandler The {@link PlayerTrackEmsgHandler} instance to handle emsg
     *     messages targeting the player. Maybe null if this is not necessary.
     */
    public DefaultSabrChunkSource(
            LoaderErrorThrower manifestLoaderErrorThrower,
            SabrManifest manifest,
            int periodIndex,
            int[] adaptationSetIndices,
            TrackSelection trackSelection,
            int trackType,
            DataSource dataSource,
            long elapsedRealtimeOffsetMs,
            int maxSegmentsPerLoad,
            boolean enableEventMessageTrack,
            List<Format> closedCaptionFormats,
            @Nullable PlayerTrackEmsgHandler playerTrackEmsgHandler) {
        this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
        this.manifest = manifest;
        this.adaptationSetIndices = adaptationSetIndices;
        this.trackSelection = trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
        this.currentFormat = trackSelection.getSelectedFormat();
        this.activeIndex = trackSelection.getSelectedIndex();
        this.trackType = trackType;
        this.dataSource = dataSource;
        this.periodIndex = periodIndex;
        this.elapsedRealtimeOffsetMs = elapsedRealtimeOffsetMs;
        this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        this.playerTrackEmsgHandler = playerTrackEmsgHandler;

        long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
        liveEdgeTimeUs = C.TIME_UNSET;
        
        sabrHeaders = new HashMap<>();
        sabrHeaders.put("Content-Type", "application/x-protobuf");
        //sabrHeaders.put("Accept-Encoding", "identity");
        sabrHeaders.put("Accept", "application/vnd.yt-ump");

        List<Representation> representations = getRepresentations();
        SabrStream stream = null;
        representationHolders = new RepresentationHolder[trackSelection.length()];
        for (int i = 0; i < representationHolders.length; i++) {
            Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
            if (stream == null && RepresentationHolder.needsExtractor(representation)) {
                stream = manifest.getSabrStream(trackType);
                stream.setFormatSelector(formatSelector);
            }
            representationHolders[i] =
                    new RepresentationHolder(
                            periodDurationUs,
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerTrackEmsgHandler,
                            stream);
        }
        this.sabrStream = stream;
    }

    @Override
    public void updateManifest(SabrManifest newManifest, int newPeriodIndex) {
        try {
            manifest = newManifest;
            periodIndex = newPeriodIndex;
            long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
            List<Representation> representations = getRepresentations();
            for (int i = 0; i < representationHolders.length; i++) {
                Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
                representationHolders[i] =
                        representationHolders[i].copyWithNewRepresentation(periodDurationUs, representation);
            }
        } catch (BehindLiveWindowException e) {
            fatalError = e;
        }
    }

    @Override
    public void updateTrackSelection(TrackSelection trackSelection) {
        this.trackSelection = trackSelection;
        // Car build: a different selected format is picked up by the next getNextChunk (maybeSwitchFormat), so the
        // stream carries on where it is instead of asking the server for the start of the video again
    }

    /**
     * Car build: the selected format changed in the middle of the stream (auto picture going back up after a weak
     * patch, or down when the decoder drops frames). Ask the server for the new format from here on: the next request
     * carries its init segment and starts where the loaded data ends, so playback never stops - no re-buffer. The
     * earlier attempt at this (ExoPlayer's own switching) re-downloaded the start of the video, because a format's
     * first request always began at 0.
     */
    private void maybeSwitchFormat(@Nullable MediaChunk previous, long loadPositionUs) {
        // Where does the loaded data end? Chunks removed from the end of the queue leave it at the first removed one's start
        boolean cut = false;
        long dataEndUs = C.TIME_UNSET;
        if (previous == null) {
            madeChunks.clear();
            madeChunkStartsUs.clear();
        } else {
            int index = madeChunks.lastIndexOf(previous);
            if (index >= 0 && index < madeChunks.size() - 1) {
                cut = true;
                dataEndUs = madeChunkStartsUs.get(index + 1);
                while (madeChunks.size() > index + 1) {
                    madeChunks.remove(madeChunks.size() - 1);
                    madeChunkStartsUs.remove(madeChunkStartsUs.size() - 1);
                }
            }
        }

        Format selected = trackSelection.getSelectedFormat();
        boolean changed = selected != null && selected != currentFormat && !selected.equals(currentFormat)
                && !manifest.dynamic; // live: untested here, keep the format the stream started with
        if (!changed && !cut) {
            return;
        }

        if (dataEndUs == C.TIME_UNSET) {
            // Right after the newest chunk: where its format's last piece ended (a chunk's own end time is only a guess -
            // one response can bring two pieces)
            FormatId oldId = formatSelector.getSelectedFormatId();
            int oldItag = oldId != null ? oldId.getItag() : -1;
            dataEndUs = sabrStream != null && sabrStream.hasSegmentStartTime(oldItag)
                    ? sabrStream.getSegmentStartTimeMs(oldItag) * 1_000L : loadPositionUs;
        }

        if (changed) {
            Log.e(TAG, "Switching format: track=" + trackType + ", from " + describe(currentFormat) + " to " + describe(selected)
                    + " at " + (previous != null ? C.usToMs(dataEndUs) + "ms" : "the start") + (cut ? " (after removed chunks)" : ""));
            currentFormat = selected;
            activeIndex = trackSelection.getSelectedIndex();
            formatSelector = createFormatSelector(trackType, trackSelection);
            if (sabrStream != null) {
                sabrStream.setFormatSelector(formatSelector);
            }
        } else {
            Log.e(TAG, "Continuing after removed chunks: track=" + trackType + ", " + describe(currentFormat)
                    + " at " + C.usToMs(dataEndUs) + "ms");
        }
        if (previous != null) {
            switchPending = true;
            switchSeekTimeUs = dataEndUs;
        }
    }

    // CancellableChunkSource: auto picture went lower and the piece that is loading, in the old version, comes in too slowly

    @Override
    public boolean shouldCancelLoad(long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue,
                                    long loadedUntilUs) {
        if (fatalError != null || manifest.dynamic || sabrStream == null || !(trackSelection instanceof PinnedTrackSelection)) {
            return false;
        }
        PinnedTrackSelection pinned = (PinnedTrackSelection) trackSelection;
        long budgetUs = pinned.getAbandonBudgetUs(loadingChunk.trackFormat);
        if (budgetUs == C.TIME_UNSET) {
            return false;
        }
        FormatId formatId = formatSelector.getSelectedFormatId(); // (the format of the request that is running)
        long loaded = loadingChunk.bytesLoaded();
        long left = sabrStream.getLoadingBytesLeft(formatId != null ? formatId.getItag() : -1);
        long durationUs = C.TIME_UNSET;
        if (left >= 0) {
            durationUs = sabrStream.getLoadingDurationMs() * 1_000L;
        } else if (loaded > 0) {
            // The response has begun, but no piece of video is coming in at this moment (before its first one, between
            // two, or it is complete): decide when the next piece's size is known
            return false;
        }
        boolean cancel = ChunkAbandonPolicy.shouldAbandon(left, loadingChunk.trackFormat, loaded, budgetUs,
                pinned.getPendingFormat(), durationUs);
        if (cancel) {
            Log.e(TAG, "Giving up the chunk that is loading: track=" + trackType + ", " + describe(loadingChunk.trackFormat)
                    + ", " + loaded + " bytes so far, " + left + " to go, may take " + C.usToMs(budgetUs) + "ms");
        }
        return cancel;
    }

    @Override
    public void onChunkLoadCanceled(Chunk chunk) {
        // Nothing to do here: the next getNextChunk sees that the queue ends before this chunk (maybeSwitchFormat)
    }

    /** The format being loaded: the selection's, unless a switch was refused (live). */
    private Format chunkFormat() {
        return trackSelection.getFormat(activeIndex);
    }

    private static String describe(@Nullable Format f) {
        return f == null ? "none" : f.id + " " + f.width + "x" + f.height + " " + f.bitrate + "bps";
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
        // Segments are aligned across representations, so any segment index will do.
        //for (RepresentationHolder representationHolder : representationHolders) {
        //    if (representationHolder.segmentIndex != null) {
        //        long segmentNum = representationHolder.getSegmentNum(positionUs);
        //        long firstSyncUs = representationHolder.getSegmentStartTimeUs(segmentNum);
        //        long secondSyncUs =
        //                firstSyncUs < positionUs && segmentNum < representationHolder.getSegmentCount() - 1
        //                        ? representationHolder.getSegmentStartTimeUs(segmentNum + 1)
        //                        : firstSyncUs;
        //        return Util.resolveSeekPositionUs(positionUs, seekParameters, firstSyncUs, secondSyncUs);
        //    }
        //}

        // Clamp a seek target that lands at/past the end of the video. Without this, seeking to
        // exactly periodDurationUs (e.g. the app's "pause on end" logic parking the player at
        // getDurationMs()) leaves nothing left to buffer, so getNextChunk() immediately re-signals
        // endOfStream, which re-triggers STATE_ENDED, which re-triggers that same seek-to-end logic
        // again - an infinite onTracksChanged()/STATE_ENDED loop every time playback reaches the end.
        for (RepresentationHolder representationHolder : representationHolders) {
            long periodDurationUs = representationHolder.periodDurationUs;
            if (periodDurationUs != C.TIME_UNSET && positionUs >= periodDurationUs) {
                // The constant is usually smaller than the real value in sabrStream.getEndOfStreamSeekToleranceMs()
                return Math.max(0, periodDurationUs - END_OF_STREAM_SEEK_TOLERANCE_US);
            }
        }
        // We don't have a segment index to adjust the seek position with yet.
        return positionUs;
    }

    @Override
    public void maybeThrowError() throws IOException {
        if (fatalError != null) {
            throw fatalError;
        } else {
            manifestLoaderErrorThrower.maybeThrowError();
        }
    }

    @Override
    public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        if (fatalError != null || trackSelection.length() < 2
                || (manifest.dynamic && trackSelection instanceof PinnedTrackSelection)) { // live: no switching (see maybeSwitchFormat)
            return queue.size();
        }
        return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
    }

    @Override
    public void getNextChunk(long playbackPositionUs, long loadPositionUs, List<? extends MediaChunk> queue, ChunkHolder out) {
        if (fatalError != null) {
            return;
        }

        long bufferedDurationUs = loadPositionUs - playbackPositionUs;
        long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
        long presentationPositionUs =
                C.msToUs(manifest.availabilityStartTimeMs)
                        + C.msToUs(manifest.getPeriod(periodIndex).startMs)
                        + loadPositionUs;

        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestBeforeLoadingNextChunk(
                presentationPositionUs)) {
            return;
        }

        long nowUnixTimeUs = getNowUnixTimeUs();
        MediaChunk previous = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
        //for (int i = 0; i < chunkIterators.length; i++) {
        //    RepresentationHolder representationHolder = representationHolders[i];
        //    if (representationHolder.segmentIndex == null) {
        //        chunkIterators[i] = MediaChunkIterator.EMPTY;
        //    } else {
        //        long firstAvailableSegmentNum =
        //                representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long lastAvailableSegmentNum =
        //                representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long segmentNum =
        //                getSegmentNum(
        //                        representationHolder,
        //                        previous,
        //                        loadPositionUs,
        //                        firstAvailableSegmentNum,
        //                        lastAvailableSegmentNum);
        //        if (segmentNum < firstAvailableSegmentNum) {
        //            chunkIterators[i] = MediaChunkIterator.EMPTY;
        //        } else {
        //            chunkIterators[i] =
        //                    new RepresentationSegmentIterator(
        //                            representationHolder, segmentNum, lastAvailableSegmentNum);
        //        }
        //    }
        //}

        trackSelection.updateSelectedTrack(
                playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, chunkIterators);
        maybeSwitchFormat(previous, loadPositionUs);

        RepresentationHolder representationHolder =
                representationHolders[activeIndex];

        // External captions contain the entire subtitle track in one sample.
        if (representationHolder.extractorWrapper == null && previous != null) {
            out.endOfStream = true;
            return;
        }

        boolean withInit = false;
        if (representationHolder.extractorWrapper != null) {
            Representation selectedRepresentation = representationHolder.representation;
            RangedUri pendingInitializationUri = null;
            RangedUri pendingIndexUri = null;
            if (representationHolder.extractorWrapper.getSampleFormats() == null) {
                pendingInitializationUri = selectedRepresentation.getInitializationUri();
            }
            // No segment index in SABR
            //if (representationHolder.segmentIndex == null) {
            //    pendingIndexUri = selectedRepresentation.getIndexUri();
            //}
            //if (pendingInitializationUri != null || pendingIndexUri != null) {
            if (pendingInitializationUri != null) {
                if (canLoadInitWithMedia(queue)) {
                    // Car build: at the start, one request brings the init segment and the first media together and the
                    // media chunk reads both. A separate init request cost one more server round trip, and its
                    // response carried the first seconds of media too, which the init chunk threw away (downloaded twice).
                    withInit = true;
                } else {
                    // We have initialization and/or index requests to make.
                    out.chunk = newInitializationChunk(representationHolder, dataSource,
                            chunkFormat(), trackSelection.getSelectionReason(),
                            trackSelection.getSelectionData(), pendingInitializationUri, pendingIndexUri);
                    return;
                }
            }
        }

        long periodDurationUs = representationHolder.periodDurationUs;
        boolean periodEnded = periodDurationUs != C.TIME_UNSET;

        // FIX: fire ending event on a video end
        // Tolerance needed: on some (usually short/sponsor at end) videos, real segment duration falls a fraction
        // of a second short of periodDurationUs, so a strict ">=" never fires and getNextChunk() loops forever
        if (periodEnded && (loadPositionUs + NEAR_END_TOLERANCE_US) >= periodDurationUs) {
            // No segment index in SABR, so we can't compare per-segment boundaries like stock
            // DASH does — comparing loadPositionUs directly against periodDurationUs is the
            // SABR equivalent. Without this, getNextChunk() keeps firing "next chunk" requests
            // past the real end of the video forever, and the player never reaches STATE_ENDED.
            out.endOfStream = true;
            return;
        }

        //if (representationHolder.getSegmentCount() == 0) {
        //    // The index doesn't define any segments.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //long firstAvailableSegmentNum =
        //        representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //long lastAvailableSegmentNum =
        //        representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);

        //updateLiveEdgeTimeUs(representationHolder, lastAvailableSegmentNum);

        //long segmentNum =
        //        getSegmentNum(
        //                representationHolder,
        //                previous,
        //                loadPositionUs,
        //                firstAvailableSegmentNum,
        //                lastAvailableSegmentNum);
        //if (segmentNum < firstAvailableSegmentNum) {
        //    // This is before the first chunk in the current manifest.
        //    fatalError = new BehindLiveWindowException();
        //    return;
        //}
        //
        //if (segmentNum > lastAvailableSegmentNum
        //        || (missingLastSegment && segmentNum >= lastAvailableSegmentNum)) {
        //    // The segment is beyond the end of the period.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //if (periodEnded && representationHolder.getSegmentStartTimeUs(segmentNum) >= periodDurationUs) {
        //    // The period duration clips the period to a position before the segment.
        //    out.endOfStream = true;
        //    return;
        //}

        //int maxSegmentCount =
        //        (int) Math.min(maxSegmentsPerLoad, lastAvailableSegmentNum - segmentNum + 1);
        //if (periodDurationUs != C.TIME_UNSET) {
        //    while (maxSegmentCount > 1
        //            && representationHolder.getSegmentStartTimeUs(segmentNum + maxSegmentCount - 1)
        //            >= periodDurationUs) {
        //        // The period duration clips the period to a position before the last segment in the range
        //        // [segmentNum, segmentNum + maxSegmentCount - 1]. Reduce maxSegmentCount.
        //        maxSegmentCount--;
        //    }
        //}

        // Car build: a format change (or a continuation after removed chunks) asks from where the loaded data ends
        boolean midStream = switchPending && !queue.isEmpty();
        long seekTimeUs = midStream ? switchSeekTimeUs : queue.isEmpty() ? loadPositionUs : C.TIME_UNSET;
        switchPending = false;
        if (sabrStream != null) {
            if (seekTimeUs != C.TIME_UNSET) {
                pendingSeekTimeUs = seekTimeUs;
                pendingSeekMidStream = midStream;
            } else if (pendingSeekTimeUs != C.TIME_UNSET) {
                FormatId formatId = formatSelector.getSelectedFormatId();
                if (sabrStream.hasSegmentStartTime(formatId != null ? formatId.getItag() : -1)) {
                    pendingSeekTimeUs = C.TIME_UNSET; // the seek landed
                } else {
                    // FIX: the seek chunk failed (e.g. retried on an alternate media network) before
                    // any segment arrived. The stream time is reset to 0 at this point, so without the
                    // seek target the server would send segments from the start of the video.
                    Log.w(TAG, "Retrying the seek to " + C.usToMs(pendingSeekTimeUs) + "ms");
                    seekTimeUs = pendingSeekTimeUs;
                    midStream = pendingSeekMidStream;
                }
            }
        }
        out.chunk =
                newMediaChunk(
                        representationHolder,
                        dataSource,
                        trackType,
                        chunkFormat(),
                        trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(),
                        C.INDEX_UNSET, // TODO: does sabr has segment num?
                        //segmentNum,
                        //maxSegmentCount,
                        seekTimeUs,
                        withInit,
                        midStream);
        if (withInit) {
            initWithMediaChunk = out.chunk;
        }
    }

    /**
     * Car build: the init segment can come with the first media only at the very start (or a seek before anything was
     * loaded) of a regular video. Quality switches and live streams keep the separate init request.
     */
    private boolean canLoadInitWithMedia(List<? extends MediaChunk> queue) {
        return !sInitWithMediaBroken && (queue.isEmpty() || switchPending || pendingSeekTimeUs != C.TIME_UNSET)
                && sabrStream != null && !manifest.dynamic;
    }

    /** Car build: if the combined first request ever goes wrong, go back to separate init requests for good. */
    private void checkInitWithMedia(Chunk chunk, @Nullable Exception error) {
        if (chunk != initWithMediaChunk) {
            return;
        }
        initWithMediaChunk = null;

        boolean noInit = false;
        if (error == null) {
            int index = trackSelection.indexOf(chunk.trackFormat);
            RepresentationHolder holder = index >= 0 && index < representationHolders.length ? representationHolders[index] : null;
            noInit = holder != null && holder.extractorWrapper != null && holder.extractorWrapper.getSampleFormats() == null;
        }

        // A plain network failure or a server control signal says nothing about the request itself
        Throwable cause = error != null ? error.getCause() : null;
        boolean controlSignal = cause instanceof SabrReloadPlayerError || cause instanceof SabrBackoffError;
        boolean broken = noInit || (error != null && !controlSignal && (error instanceof ParserException
                || error instanceof Loader.UnexpectedLoaderException || !(error instanceof IOException)));
        if (broken) {
            Log.e(TAG, "Init segment with the first media failed. Using separate init requests from now on. " + error);
            sInitWithMediaBroken = true;
        }
    }

    @Override
    public void onChunkLoadCompleted(Chunk chunk) {
        checkInitWithMedia(chunk, null);
        if (chunk instanceof InitializationChunk) {
            InitializationChunk initializationChunk = (InitializationChunk) chunk;
            int trackIndex = trackSelection.indexOf(initializationChunk.trackFormat);
            RepresentationHolder representationHolder = representationHolders[trackIndex];
            // The null check avoids overwriting an index obtained from the manifest with one obtained
            // from the stream. If the manifest defines an index then the stream shouldn't, but in cases
            // where it does we should ignore it.
            //if (representationHolder.segmentIndex == null) {
            //    SeekMap seekMap = representationHolder.extractorWrapper.getSeekMap();
            //    if (seekMap != null) {
            //        representationHolders[trackIndex] =
            //                representationHolder.copyWithNewSegmentIndex(
            //                        new SabrWrappingSegmentIndex(
            //                                (ChunkIndex) seekMap,
            //                                representationHolder.representation.presentationTimeOffsetUs));
            //    }
            //}
        }
        if (playerTrackEmsgHandler != null) {
            playerTrackEmsgHandler.onChunkLoadCompleted(chunk);
        }
    }

    @Override
    public boolean onChunkLoadError(Chunk chunk, boolean cancelable, Exception e, long blacklistDurationMs) {
        Log.e(TAG, "Chunk load failed: " + e.getMessage());
        checkInitWithMedia(chunk, e);

        // A reload request is not a transport failure. Handing it back as "handled" would
        // retry the very same request at full speed. Let it fall through to the
        // LoadErrorHandlingPolicy, which stashes the token and fails the load.
        Throwable cause = e.getCause();
        if (cause instanceof SabrReloadPlayerError || cause instanceof SabrBackoffError) {
            Log.e(TAG, "SABR control signal, letting the error policy decide: " + cause.getMessage());
            return false;
        }

        if (!cancelable) {
            return false;
        }
        if (!(chunk instanceof SingleSampleMediaChunk) && isEndpointConnectionFailure(chunk, e)) {
            if (manifest.maybeUseNextCdn(chunk.dataSpec.uri.toString())) {
                Log.w(TAG, "Retrying SABR request on an alternate media network");
                return true;
            }

            // All representations use the same SABR endpoint. Do not rapidly
            // blacklist qualities when the endpoint itself is unreachable.
            return false;
        }
        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestOnLoadingError(chunk)) {
            return true;
        }
        // Workaround for missing segment at the end of the period
        //if (!manifest.dynamic && chunk instanceof MediaChunk
        //        && e instanceof InvalidResponseCodeException
        //        && ((InvalidResponseCodeException) e).responseCode == 404) {
        //    RepresentationHolder representationHolder =
        //            representationHolders[trackSelection.indexOf(chunk.trackFormat)];
        //    int segmentCount = representationHolder.getSegmentCount();
        //    if (segmentCount != SabrSegmentIndex.INDEX_UNBOUNDED && segmentCount != 0) {
        //        long lastAvailableSegmentNum = representationHolder.getFirstSegmentNum() + segmentCount - 1;
        //        if (((MediaChunk) chunk).getNextChunkIndex() > lastAvailableSegmentNum) {
        //            missingLastSegment = true;
        //            return true;
        //        }
        //    }
        //}
        return blacklistDurationMs != C.TIME_UNSET
                && !(trackSelection instanceof PinnedTrackSelection) // Car build: the app picks the track (see the interface)
                && trackSelection.blacklist(trackSelection.indexOf(chunk.trackFormat), blacklistDurationMs);
    }

    private static boolean isEndpointConnectionFailure(Chunk chunk, Exception error) {
        if (!(error instanceof IOException) || error instanceof InvalidResponseCodeException) {
            return false;
        }

        // FIX: a media network that stalls in the middle of a response (read timeout) is as
        // unusable as one that doesn't answer at all. Retrying the same network keeps stalling.
        return chunk.bytesLoaded() == 0 || hasCause(error, SocketTimeoutException.class);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }

        return false;
    }

    private ArrayList<Representation> getRepresentations() {
        List<AdaptationSet> manifestAdaptationSets = manifest.getPeriod(periodIndex).adaptationSets;
        ArrayList<Representation> representations = new ArrayList<>();
        for (int adaptationSetIndex : adaptationSetIndices) {
            representations.addAll(manifestAdaptationSets.get(adaptationSetIndex).representations);
        }
        return representations;
    }

    private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
        boolean resolveTimeToLiveEdgePossible = manifest.dynamic && liveEdgeTimeUs != C.TIME_UNSET;
        return resolveTimeToLiveEdgePossible ? liveEdgeTimeUs - playbackPositionUs : C.TIME_UNSET;
    }

    private long getNowUnixTimeUs() {
        if (elapsedRealtimeOffsetMs != 0) {
            return (SystemClock.elapsedRealtime() + elapsedRealtimeOffsetMs) * 1000;
        } else {
            return System.currentTimeMillis() * 1000;
        }
    }

    //private long getSegmentNum(
    //        RepresentationHolder representationHolder,
    //        @Nullable MediaChunk previousChunk,
    //        long loadPositionUs,
    //        long firstAvailableSegmentNum,
    //        long lastAvailableSegmentNum) {
    //    return previousChunk != null
    //            ? previousChunk.getNextChunkIndex()
    //            : Util.constrainValue(
    //            representationHolder.getSegmentNum(loadPositionUs),
    //            firstAvailableSegmentNum,
    //            lastAvailableSegmentNum);
    //}

    //private void updateLiveEdgeTimeUs(
    //        RepresentationHolder representationHolder, long lastAvailableSegmentNum) {
    //    liveEdgeTimeUs = manifest.dynamic
    //            ? representationHolder.getSegmentEndTimeUs(lastAvailableSegmentNum) : C.TIME_UNSET;
    //}

    protected Chunk newInitializationChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            RangedUri initializationUri,
            RangedUri indexUri) {
        //RangedUri requestUri;
        //String baseUrl = representationHolder.representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());
        //if (initializationUri != null) {
        //    // It's common for initialization and index data to be stored adjacently. Attempt to merge
        //    // the two requests together to request both at once.
        //    requestUri = initializationUri.attemptMerge(indexUri, baseUrl);
        //    if (requestUri == null) {
        //        requestUri = initializationUri;
        //    }
        //} else {
        //    requestUri = indexUri;
        //}
        // NOTE: first protobuf request (before the video start off)
        // MOD: add protobuf data
        //DataSpec dataSpec = new DataSpec(requestUri.resolveUri(baseUrl), requestUri.start,
        //        requestUri.length, representationHolder.representation.getCacheKey());
        DataSpec dataSpec = new DataSpec(
                //Uri.parse(sabrStream.getRequestUrl()),
                Uri.parse(manifest.getRequestUrl(trackType)),
                DataSpec.HTTP_METHOD_POST,
                //sabrStream.createVideoPlaybackAbrRequest(trackType, true).toByteArray(),
                // Car build: a format switch in the middle of the stream asks for the init segment from where the loaded
                // data ends (the media that comes with it is thrown away here and asked for again by the media chunk)
                manifest.createVideoPlaybackAbrRequest(trackType, true, switchPending ? switchSeekTimeUs : C.TIME_UNSET).toByteArray(),
                0, 0, C.LENGTH_UNSET,
                //requestUri.start,
                //requestUri.start,
                //requestUri.length,
                representationHolder.representation.getCacheKey(),
                0,
                sabrHeaders);
        Log.e(TAG, "Load init chunk: track=" + trackType + ", rn=" + manifest.getSabrRequestNumber());
        return new InitializationChunk(dataSource, dataSpec, trackFormat,
                trackSelectionReason, trackSelectionData, representationHolder.extractorWrapper);
    }

    protected Chunk newMediaChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            int trackType,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            long firstSegmentNum,
            //int maxSegmentCount,
            long seekTimeUs,
            boolean withInit,
            boolean midStream) {
        Representation representation = representationHolder.representation;
        if (representationHolder.extractorWrapper == null) {
            DataSpec dataSpec = new DataSpec(
                    Uri.parse(representation.baseUrl), DataSpec.HTTP_METHOD_GET, null,
                    0, 0, C.LENGTH_UNSET, representation.getCacheKey(), 0,
                    manifest.visitorCookie != null
                            ? Collections.singletonMap("Cookie", manifest.visitorCookie)
                            : Collections.emptyMap());
            return new SingleSampleMediaChunk(
                    dataSource, dataSpec, trackFormat, trackSelectionReason, trackSelectionData,
                    0, representationHolder.periodDurationUs, 0, trackType, trackFormat);
        }

        SabrStream sabrStream = Assertions.checkNotNull(this.sabrStream);
        boolean isInit = nexChunkIdx == -1;
        FormatId formatId = formatSelector.getSelectedFormatId();
        int iTag = formatId != null ? formatId.getItag() : -1;

        // FIX: seek backwards does infinite loading after 60 second but the buffer is full
        boolean isSeek = seekTimeUs != C.TIME_UNSET; // same condition used for seekTimeUs
        if (isSeek) {
            sabrStream.reset(iTag);
            nexChunkIdx = -1; // or whatever "post-init" value newMediaChunk expects
        }

        // Old code
        //if (nexChunkIdx == -1) {
        //    sabrStream.reset(iTag);
        //}

        nexChunkIdx++;

        //long startTimeMs = sabrStream.getSegmentStartTimeMs(trackType);
        //long durationMs = sabrStream.getSegmentDurationMs(trackType);
        long startTimeMs = sabrStream.getSegmentStartTimeMs(iTag);
        long durationMs = sabrStream.getSegmentDurationMs(iTag);
        // Car build: after a reset (a seek, a format change) the stream has no piece to go by: the times below would be
        // 0..one piece for a format that was played before - nonsense in the middle of a video
        boolean isSabrTimeSet = !isSeek && startTimeMs >= 0 && durationMs > 0;

        long startTimeUs = isSabrTimeSet ? startTimeMs * 1_000L : C.TIME_UNSET;
        long endTimeUs = isSabrTimeSet ? startTimeUs + (durationMs * 1_000L) : C.TIME_UNSET;
        if (midStream && isSeek) {
            // A change in the middle of the stream starts exactly where the loaded data ends (the end is a safe minimum)
            startTimeUs = seekTimeUs;
            endTimeUs = seekTimeUs;
        } else if (!isSabrTimeSet) {
            // Car build: the segment times aren't known yet (a format's first request, e.g. the one with the init
            // segment). The end must still be a real time: TIME_UNSET made ExoPlayer's buffered-duration math overflow
            // whenever the position wasn't 0 (resuming a video, a quality change mid-video), so it stopped loading
            // after this first piece and the video stalled. The start stays unset (decode-only up to the seek point).
            endTimeUs = seekTimeUs != C.TIME_UNSET ? seekTimeUs
                    : pendingSeekTimeUs != C.TIME_UNSET ? pendingSeekTimeUs : 0;
        }
        long clippedEndTimeUs = C.TIME_UNSET;
        int segmentCount = C.INDEX_UNSET; // TODO: replace with SABR value?
        //long startTimeUs = representationHolder.getSegmentStartTimeUs(firstSegmentNum);
        //RangedUri segmentUri = representationHolder.getSegmentUrl(firstSegmentNum);
        //String baseUrl = representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());

        DataSpec dataSpec = new DataSpec(
                //Uri.parse(sabrStream.getRequestUrl()),
                Uri.parse(manifest.getRequestUrl(trackType)),
                //segmentUri.resolveUri(baseUrl),
                DataSpec.HTTP_METHOD_POST,
                //sabrStream.createVideoPlaybackAbrRequest(trackType, false).toByteArray(),
                manifest.createVideoPlaybackAbrRequest(trackType, withInit,
                        midStream && isSeek ? seekTimeUs + SWITCH_INTO_SEGMENT_US : seekTimeUs).toByteArray(),
                0, 0, C.LENGTH_UNSET,
                //segmentUri.start,
                //segmentUri.start,
                //segmentUri.length,
                representation.getCacheKey(),
                0,
                sabrHeaders);
        long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        Log.e(TAG, "Load media chunk" + (withInit ? " with init" : "") + ": track=" + trackType + ", rn=" + manifest.getSabrRequestNumber()
                + ", itag=" + iTag + ", startTimeMs=" + (isSeek ? seekTimeUs / 1_000 : startTimeMs) + (isSeek ? " (seek)" : "")
                + ", backoffTimeMs=" + sabrStream.getBackoffTimeMs());
        ContainerMediaChunk chunk = new ContainerMediaChunk(
                dataSource,
                dataSpec,
                trackFormat,
                trackSelectionReason,
                trackSelectionData,
                startTimeUs,
                endTimeUs,
                seekTimeUs,
                clippedEndTimeUs,
                firstSegmentNum,
                segmentCount,
                sampleOffsetUs,
                representationHolder.extractorWrapper);
        madeChunks.add(chunk);
        madeChunkStartsUs.add(isSeek ? seekTimeUs : startTimeMs * 1_000L);
        if (madeChunks.size() > MADE_CHUNKS_KEPT) {
            madeChunks.remove(0);
            madeChunkStartsUs.remove(0);
        }
        return chunk;

        //if (representationHolder.extractorWrapper == null) {
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum);
        //    DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //            segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    return new SingleSampleMediaChunk(dataSource, dataSpec, trackFormat, trackSelectionReason,
        //            trackSelectionData, startTimeUs, endTimeUs, firstSegmentNum, trackType, trackFormat);
        //} else {
        //    int segmentCount = 1;
        //    for (int i = 1; i < maxSegmentCount; i++) {
        //        RangedUri nextSegmentUri = representationHolder.getSegmentUrl(firstSegmentNum + i);
        //        RangedUri mergedSegmentUri = segmentUri.attemptMerge(nextSegmentUri, baseUrl);
        //        if (mergedSegmentUri == null) {
        //            // Unable to merge segment fetches because the URIs do not merge.
        //            break;
        //        }
        //        segmentUri = mergedSegmentUri;
        //        segmentCount++;
        //    }
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum + segmentCount - 1);
        //    long periodDurationUs = representationHolder.periodDurationUs;
        //    long clippedEndTimeUs =
        //            periodDurationUs != C.TIME_UNSET && periodDurationUs <= endTimeUs
        //                    ? periodDurationUs
        //                    : C.TIME_UNSET;
        //    // NOTE: next protobuf requests (during the playback)
        //    // MOD: add protobuf data
        //    //DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //    //        segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    int sabrRequestNumber = sabrStream.getIncSabrRequestNumber();
        //    DataSpec dataSpec = new DataSpec(
        //            segmentUri.resolveUri(baseUrl),
        //            DataSpec.HTTP_METHOD_POST,
        //            sabrStream.buildInitVideoPlaybackAbrRequest(trackType).toByteArray(),
        //            0, 0, C.LENGTH_UNSET,
        //            //segmentUri.start,
        //            //segmentUri.start,
        //            //segmentUri.length,
        //            representation.getCacheKey(),
        //            0,
        //            sabrHeaders);
        //    long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        //    return new ContainerMediaChunk(
        //            dataSource,
        //            dataSpec,
        //            trackFormat,
        //            trackSelectionReason,
        //            trackSelectionData,
        //            startTimeUs,
        //            endTimeUs,
        //            seekTimeUs,
        //            clippedEndTimeUs,
        //            firstSegmentNum,
        //            segmentCount,
        //            sampleOffsetUs,
        //            representationHolder.extractorWrapper);
        //}
    }

    private static AudioSelector createAudioSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_AUDIO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new AudioSelector("selected_audio", false, selectedFormat);
    }

    private static VideoSelector createVideoSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_VIDEO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new VideoSelector("selected_video", false, selectedFormat);
    }

    private static CaptionSelector createCaptionSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new CaptionSelector("selected_caption", false, selectedFormat);
    }

    private static FormatSelector createFormatSelector(int trackType, TrackSelection trackSelection) {
        Format selectedFormat = trackSelection.getSelectedFormat();

        switch (trackType) {
            case C.TRACK_TYPE_AUDIO:
                return new FormatSelector("selected_audio", false, selectedFormat);
            case C.TRACK_TYPE_VIDEO:
                return new FormatSelector("selected_video", false, selectedFormat);
            case C.TRACK_TYPE_TEXT:
                return new FormatSelector("selected_caption", false, selectedFormat);
        }

        throw new IllegalStateException("Unknown track type");
    }

    /** {@link MediaChunkIterator} wrapping a {@link RepresentationHolder}. */
    //protected static final class RepresentationSegmentIterator extends BaseMediaChunkIterator {
    //
    //    private final RepresentationHolder representationHolder;
    //
    //    /**
    //     * Creates iterator.
    //     *
    //     * @param representation The {@link RepresentationHolder} to wrap.
    //     * @param firstAvailableSegmentNum The number of the first available segment.
    //     * @param lastAvailableSegmentNum The number of the last available segment.
    //     */
    //    public RepresentationSegmentIterator(
    //            RepresentationHolder representation,
    //            long firstAvailableSegmentNum,
    //            long lastAvailableSegmentNum) {
    //        super(/* fromIndex= */ firstAvailableSegmentNum, /* toIndex= */ lastAvailableSegmentNum);
    //        this.representationHolder = representation;
    //    }
    //
    //    @Override
    //    public DataSpec getDataSpec() {
    //        checkInBounds();
    //        Representation representation = representationHolder.representation;
    //        RangedUri segmentUri = representationHolder.getSegmentUrl(getCurrentIndex());
    //        Uri resolvedUri = segmentUri.resolveUri(representation.baseUrl);
    //        String cacheKey = representation.getCacheKey();
    //        return new DataSpec(resolvedUri, segmentUri.start, segmentUri.length, cacheKey);
    //    }
    //
    //    @Override
    //    public long getChunkStartTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentStartTimeUs(getCurrentIndex());
    //    }
    //
    //    @Override
    //    public long getChunkEndTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentEndTimeUs(getCurrentIndex());
    //    }
    //}

    /** Holds information about a snapshot of a single {@link Representation}. */
    protected static final class RepresentationHolder {

        /* package */ final @Nullable ChunkExtractorWrapper extractorWrapper;

        public final Representation representation;
        //public final @Nullable SabrSegmentIndex segmentIndex;

        private final long periodDurationUs;
        private final long segmentNumShift;

        /* package */ RepresentationHolder(
                long periodDurationUs,
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            this(
                    periodDurationUs,
                    representation,
                    createExtractorWrapper(
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerEmsgTrackOutput,
                            sabrStream),
                    /* segmentNumShift= */ 0
                    //representation.getIndex()
            );
        }

        private RepresentationHolder(
                long periodDurationUs,
                Representation representation,
                @Nullable ChunkExtractorWrapper extractorWrapper,
                long segmentNumShift
                //@Nullable SabrSegmentIndex segmentIndex
        ) {
            this.periodDurationUs = periodDurationUs;
            this.representation = representation;
            this.segmentNumShift = segmentNumShift;
            this.extractorWrapper = extractorWrapper;
            //this.segmentIndex = segmentIndex;
        }

        @CheckResult
            /* package */ RepresentationHolder copyWithNewRepresentation(
                long newPeriodDurationUs, Representation newRepresentation)
                throws BehindLiveWindowException {

            return new RepresentationHolder(
                    newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift);

            //SabrSegmentIndex oldIndex = representation.getIndex();
            //SabrSegmentIndex newIndex = newRepresentation.getIndex();

            //if (oldIndex == null) {
            //    // Segment numbers cannot shift if the index isn't defined by the manifest.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, oldIndex);
            //}
            //
            //if (!oldIndex.isExplicit()) {
            //    // Segment numbers cannot shift if the index isn't explicit.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //int oldIndexSegmentCount = oldIndex.getSegmentCount(newPeriodDurationUs);
            //if (oldIndexSegmentCount == 0) {
            //    // Segment numbers cannot shift if the old index was empty.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //long oldIndexFirstSegmentNum = oldIndex.getFirstSegmentNum();
            //long oldIndexStartTimeUs = oldIndex.getTimeUs(oldIndexFirstSegmentNum);
            //long oldIndexLastSegmentNum = oldIndexFirstSegmentNum + oldIndexSegmentCount - 1;
            //long oldIndexEndTimeUs =
            //        oldIndex.getTimeUs(oldIndexLastSegmentNum)
            //                + oldIndex.getDurationUs(oldIndexLastSegmentNum, newPeriodDurationUs);
            //long newIndexFirstSegmentNum = newIndex.getFirstSegmentNum();
            //long newIndexStartTimeUs = newIndex.getTimeUs(newIndexFirstSegmentNum);
            //long newSegmentNumShift = segmentNumShift;
            //if (oldIndexEndTimeUs == newIndexStartTimeUs) {
            //    // The new index continues where the old one ended, with no overlap.
            //    newSegmentNumShift += oldIndexLastSegmentNum + 1 - newIndexFirstSegmentNum;
            //} else if (oldIndexEndTimeUs < newIndexStartTimeUs) {
            //    // There's a gap between the old index and the new one which means we've slipped behind the
            //    // live window and can't proceed.
            //    throw new BehindLiveWindowException();
            //} else if (newIndexStartTimeUs < oldIndexStartTimeUs) {
            //    // The new index overlaps with (but does not have a start position contained within) the old
            //    // index. This can only happen if extra segments have been added to the start of the index.
            //    newSegmentNumShift -=
            //            newIndex.getSegmentNum(oldIndexStartTimeUs, newPeriodDurationUs)
            //                    - oldIndexFirstSegmentNum;
            //} else {
            //    // The new index overlaps with (and has a start position contained within) the old index.
            //    newSegmentNumShift +=
            //            oldIndex.getSegmentNum(newIndexStartTimeUs, newPeriodDurationUs)
            //                    - newIndexFirstSegmentNum;
            //}
            //return new RepresentationHolder(
            //        newPeriodDurationUs, newRepresentation, extractorWrapper, newSegmentNumShift, newIndex);
        }

        //@CheckResult
        //    /* package */ RepresentationHolder copyWithNewSegmentIndex(SabrSegmentIndex segmentIndex) {
        //    return new RepresentationHolder(
        //            periodDurationUs, representation, extractorWrapper, segmentNumShift, segmentIndex);
        //}

        //public long getFirstSegmentNum() {
        //    return segmentIndex.getFirstSegmentNum() + segmentNumShift;
        //}
        //
        //public int getSegmentCount() {
        //    return segmentIndex.getSegmentCount(periodDurationUs);
        //}
        //
        //public long getSegmentStartTimeUs(long segmentNum) {
        //    return segmentIndex.getTimeUs(segmentNum - segmentNumShift);
        //}
        //
        //public long getSegmentEndTimeUs(long segmentNum) {
        //    return getSegmentStartTimeUs(segmentNum)
        //            + segmentIndex.getDurationUs(segmentNum - segmentNumShift, periodDurationUs);
        //}
        //
        //public long getSegmentNum(long positionUs) {
        //    return segmentIndex.getSegmentNum(positionUs, periodDurationUs) + segmentNumShift;
        //}
        //
        //public RangedUri getSegmentUrl(long segmentNum) {
        //    return segmentIndex.getSegmentUrl(segmentNum - segmentNumShift);
        //}

        //public long getFirstAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    if (getSegmentCount() == SabrSegmentIndex.INDEX_UNBOUNDED
        //            && manifest.timeShiftBufferDepthMs != C.TIME_UNSET) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        long bufferDepthUs = C.msToUs(manifest.timeShiftBufferDepthMs);
        //        return Math.max(
        //                getFirstSegmentNum(), getSegmentNum(liveEdgeTimeInPeriodUs - bufferDepthUs));
        //    }
        //    return getFirstSegmentNum();
        //}
        //
        //public long getLastAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    int availableSegmentCount = getSegmentCount();
        //    if (availableSegmentCount == SabrSegmentIndex.INDEX_UNBOUNDED) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        // getSegmentNum(liveEdgeTimeInPeriodUs) will not be completed yet, so subtract one to get
        //        // the index of the last completed segment.
        //        return getSegmentNum(liveEdgeTimeInPeriodUs) - 1;
        //    }
        //    return getFirstSegmentNum() + availableSegmentCount - 1;
        //}

        private static boolean mimeTypeIsWebm(String mimeType) {
            return mimeType.startsWith(MimeTypes.VIDEO_WEBM) || mimeType.startsWith(MimeTypes.AUDIO_WEBM)
                    || mimeType.startsWith(MimeTypes.APPLICATION_WEBM);
        }

        private static boolean mimeTypeIsRawText(String mimeType) {
            return MimeTypes.isText(mimeType) || MimeTypes.APPLICATION_TTML.equals(mimeType);
        }

        private static boolean needsExtractor(Representation representation) {
            String containerMimeType = representation.format.containerMimeType;
            return containerMimeType != null && !mimeTypeIsRawText(containerMimeType);
        }

        private static @Nullable ChunkExtractorWrapper createExtractorWrapper(
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            String containerMimeType = representation.format.containerMimeType;
            if (!needsExtractor(representation)) {
                return null;
            }

            // MOD: replaced with SabrExtractor
            //Extractor extractor;
            //if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
            //    extractor = new RawCcExtractor(representation.format);
            //} else if (mimeTypeIsWebm(containerMimeType)) {
            //    extractor = new MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES);
            //} else {
            //    int flags = 0;
            //    if (enableEventMessageTrack) {
            //        flags |= FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK;
            //    }
            //    extractor =
            //            new FragmentedMp4Extractor(
            //                    flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput);
            //}

            //Extractor extractor = new SabrExtractor(trackType, representation.format, sabrStream);

            Extractor extractor;
            if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
                extractor = new RawCcExtractor(representation.format);
            } else if (mimeTypeIsWebm(containerMimeType)) {
                extractor = new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES, sabrStream);
            } else {
                int flags = 0;
                if (enableEventMessageTrack) {
                    flags |= SabrFragmentedMp4Adapter.FLAG_ENABLE_EMSG_TRACK;
                }
                extractor =
                        new SabrFragmentedMp4Adapter(
                                flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput, sabrStream);
            }

            // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
            // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
            return new ChunkExtractorWrapper(extractor, trackType, representation.format);
        }
    }
}
