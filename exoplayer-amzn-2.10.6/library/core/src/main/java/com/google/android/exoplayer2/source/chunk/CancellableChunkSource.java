package com.google.android.exoplayer2.source.chunk;

import java.util.List;

/**
 * Car build: a {@link ChunkSource} that can give up the chunk that is loading (backported idea from newer ExoPlayer
 * versions). On a weak signal a piece of video in the old, heavier quality can take tens of seconds to come in;
 * when auto picture has already decided to go lower and little is loaded ahead, waiting for it means a stall.
 * {@link ChunkSampleStream} asks while a media chunk loads, cancels it when told to, removes what it has delivered
 * so far and continues with the next call to {@link ChunkSource#getNextChunk}.
 */
public interface CancellableChunkSource {
    /**
     * Whether the load of {@code loadingChunk} should be given up. Only asked for a media chunk no sample has been
     * read from yet.
     */
    boolean shouldCancelLoad(long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue);

    /** {@code chunk} was cancelled on request and everything it delivered was removed from the queue. */
    void onChunkLoadCanceled(Chunk chunk);
}
