package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.car.CarErrors;
import android.annotation.SuppressLint;

import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector.OnLongBuffering;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

public class ErrorFixerController extends BasePlayerController implements OnLongBuffering {
    private static final String TAG = ErrorFixerController.class.getSimpleName();
    private static final long STREAM_END_THRESHOLD_MS = 180_000;
    private static final int MAX_SABR_ERROR_FIX_ATTEMPTS = 3;
    private final BufferingDetector mBufferingDetector = new BufferingDetector(this);
    private VideoLoaderController mVideoLoaderController;
    private String mSabrErrorFixVideoId;
    private int mSabrErrorFixAttempts;

    @Override
    public void onInit() {
        mVideoLoaderController = getController(VideoLoaderController.class);
    }

    private final Runnable mResumeWhenOnline = () -> {
        if (getPlayer() != null && mVideoLoaderController != null && !isStreamEnded()) {
            mVideoLoaderController.reloadVideo();
        }
    };

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        Log.e(TAG, "Player error occurred: %s. Trying to fix…", type);

        mBufferingDetector.reset();

        // Car build: lost the signal. Settings are fine; reload the same video once it is back.
        if (!CarErrors.isOnline(getContext()) && (error == null || CarErrors.isNetwork(error))) {
            Log.w(TAG, "Player error while offline: waiting for the signal");
            MessageHelpers.showMessage(getContext(), CarErrors.noInternet(getContext()));
            CarErrors.whenOnline(getContext(), mResumeWhenOnline);
            return;
        }

        runEngineErrorAction(type, rendererIndex, error);
    }

    @Override
    public void onLongBuffering() {
        if (getPlayer() == null) {
            return;
        }

        // Car build: no signal (tunnel, dead zone) is not a broken client or DNS. Don't switch settings or restart
        // the app; keep the video and carry on loading when the signal is back.
        if (!isStreamEnded() && !CarErrors.isOnline(getContext())) {
            Log.w(TAG, "Long buffering while offline: waiting for the signal");
            MessageHelpers.showMessage(getContext(), CarErrors.noInternet(getContext()));
            CarErrors.whenOnline(getContext(), mResumeWhenOnline);
            return;
        }

        if (isStreamEnded()) {
            getMainController().onPlayEnd();
            return;
        }

        // Car build: data is arriving, only slowly (a weak signal): auto picture is stepping the quality down - give
        // that time instead of switching the YouTube client, reloading, or restarting the app (all of which start the
        // loading over on the same weak link). Those fixes still run once nothing arrives at all for a while.
        if (com.liskovsoft.smartyoutubetv2.common.car.CarPlaybackMonitor.shouldDeferUpstreamFix()) {
            Log.w(TAG, "Long buffering on a weak signal: leaving it to auto picture for now");
            mBufferingDetector.onStartBuffering(); // ask again in 20 s
            return;
        }

        if (isOfflineVideo() && isSubtitlesEnabled()) {
            // Long loading subtitles cause hangs
            disableSubtitles();
            mVideoLoaderController.reloadVideo();
        } else if (isOfflineVideo() && !mBufferingDetector.isPlayable()) {
            int requiredEngine = PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP;
            int customDns = PlayerTweaksData.DNS_TYPE_GOOGLE;
            boolean isCustomEngineEnabled = getPlayerTweaksData().getPlayerDataSource() == requiredEngine;
            boolean isCustomDnsEnabled = getPlayerTweaksData().getPreferredDnsType() == customDns;
            boolean isCustomDnsActive = isCustomEngineEnabled && isCustomDnsEnabled;
            if (!isCustomDnsActive && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                // Wrong DNS resolving could cause hanging at start
                // Do switch to only engine that respects custom DNS settings
                Log.w(TAG, "Fixing incorrect DNS resolution. App restart needed...");
                getPlayerTweaksData().setPlayerDataSource(requiredEngine);
                getPlayerTweaksData().setPreferredDnsType(customDns);
                if (!isCustomDnsEnabled) {
                    // Restart app to reinit OkHttp internal objects
                    Utils.restartTheApp(getContext());
                } else {
                    mVideoLoaderController.restartEngine();
                }
            } else {
                // Also, some clients like ANDROID_REEL may just hang at start
                Log.w(TAG, "Fixing stalled client...");
                YouTubeServiceManager.instance().switchNextClientNow();
                mVideoLoaderController.reloadVideo();
            }
        } else {
            // NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.
            // This will change current format to 'Disabled'. Do restartEngine() instead.
            //lowerVideoQuality();
            //mVideoLoaderController.restartEngine();

            // SABR may hang if the server issues a high backoffTime
            mVideoLoaderController.reloadVideo();
        }
    }

    @Override
    public void onBuffering() {
        mBufferingDetector.onStartBuffering();
    }

    @Override
    public void onSeekEnd() {
        mBufferingDetector.reset();
        // Needed to detect additional buffering (e.g. hanged clients).
        // Don't worry this event will be canceled by subsequent onPlay() or onPause() if everything is ok.
        mBufferingDetector.onStartBuffering();
    }

    @Override
    public void onPlay() {
        mBufferingDetector.onStopBuffering();
        mSabrErrorFixAttempts = 0;
    }

    @Override
    public void onPause() {
        mBufferingDetector.onStopBuffering();
    }

    @Override
    public void onNewVideo(Video item) {
        mBufferingDetector.start();
    }

    @Override
    public void onFinish() {
        mBufferingDetector.reset();
    }

    @Override
    public void onEngineReleased() {
        mBufferingDetector.reset();
    }

    private void runEngineErrorAction(int type, int rendererIndex, Throwable error) {
        // Hide begin errors in embed mode (e.g. wrong date/time: unable to connect to...)
        if (isEmbedPlayer() && getPlayer() != null && getPlayer().getPositionMs() == 0) {
            getPlayer().finish();
            return;
        }

        if (isStreamEnded()) {
            // Url no longer works (e.g. live stream ended)
            getMainController().onPlayEnd();
            return;
        }

        if (applySabrReloadPlayerAction(error)) {
            return;
        }

        if (applySabrBackoffAction(error)) {
            return;
        }

        applyEngineErrorAction(type, rendererIndex, error);
    }

    /**
     * SABR server sent ReloadPlayerResponse. It won't send any media until the player response is re-fetched with the token.
     */
    private boolean applySabrReloadPlayerAction(Throwable error) {
        SabrReloadPlayerError reloadError = null;
        Video video = getVideo();

        Throwable cause = error.getCause();
        if (cause instanceof SabrReloadPlayerError) {
            reloadError = (SabrReloadPlayerError) cause;
        }

        if (reloadError == null || video == null) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mSabrErrorFixVideoId)) {
            mSabrErrorFixVideoId = video.videoId;
            mSabrErrorFixAttempts = 0;
        }

        // Reload doesn't help. Fallback to the common error handling (switch the client).
        if (++mSabrErrorFixAttempts > MAX_SABR_ERROR_FIX_ATTEMPTS) {
            return false;
        }

        Log.w(TAG, "%s. Attempt: %s", reloadError.getMessage(), mSabrErrorFixAttempts); // car build: silent retry

        YouTubeServiceManager.instance().reloadPlayerResponse(video.videoId, reloadError.reloadPlaybackToken);
        mVideoLoaderController.reloadVideo();

        return true;
    }

    /**
     * The server told us to back off, honor it instead of re-requesting
     * immediately - otherwise we burn exactly the waiting period the server asked for.
     */
    private boolean applySabrBackoffAction(Throwable error) {
        SabrBackoffError backoffError = null;
        Video video = getVideo();

        Throwable cause = error.getCause();
        if (cause instanceof SabrBackoffError) {
            backoffError = (SabrBackoffError) cause;
        }

        if (backoffError == null || video == null) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mSabrErrorFixVideoId)) {
            mSabrErrorFixVideoId = video.videoId;
            mSabrErrorFixAttempts = 0;
        }

        // Reload doesn't help. Fallback to the common error handling (switch the client).
        if (++mSabrErrorFixAttempts > MAX_SABR_ERROR_FIX_ATTEMPTS) {
            return false;
        }

        Log.w(TAG, "%s. Attempt: %s", backoffError.getMessage(), mSabrErrorFixAttempts); // car build: silent retry

        // SABR may hang if the server issues a high backoffTime
        mVideoLoaderController.reloadVideo();

        return true;
    }

    private void applyEngineErrorAction(int type, int rendererIndex, Throwable error) {
        boolean restartEngine = true;
        boolean showMessage = true;
        String errorContent = error != null ? error.getMessage() : null;
        String errorTitle = getErrorTitle(type, rendererIndex);
        String errorMessage = errorTitle + "\n" + errorContent;

        if (Helpers.startsWithAny(errorContent, "Unable to connect to")) {
            // No internet connection or WRONG DATE on the device
            // Recently this message starting to show for other unknown reasons
            if (!getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                switchNextEngine();
            }
        } else if (error instanceof OutOfMemoryError || (error != null && error.getCause() instanceof OutOfMemoryError)) {
            if (getPlayerTweaksData().getPlayerDataSource() == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                // OkHttp has memory leak problems
                enableFasterDataSource();
            } else if (getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGH || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGHEST) {
                getPlayerData().setVideoBufferType(PlayerData.BUFFER_MEDIUM);
            } else {
                lowerVideoQuality(); // NOTE: restart engine required
            }
        } else if (Helpers.containsAny(errorContent, "Exception in CronetUrlRequest") && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
            if (getVideo() != null && !getVideo().isLive) { // Finished live stream may provoke errors in Cronet
                getPlayerTweaksData().setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_SOURCE && rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN) {
            // NOTE: Starts with any (url deciphered incorrectly)
            // "Response code: 403" (poToken error, forbidden)
            // "Response code: 404" (not sure whether below helps)
            // "Response code: 503" (not sure whether below helps)
            // "Response code: 400" (not sure whether below helps)
            // "Response code: 429" (subtitle error, too many requests)
            // "Response code: 500" (subtitle error, generic server error)

            // NOTE: Fixing too many requests or network issues
            // NOTE: All these errors have unknown renderer (-1)
            // "Unable to connect to", "Invalid NAL length", "Response code: 421",
            // "Response code: 404", "Response code: 429", "Invalid integer size",
            // "Unexpected ArrayIndexOutOfBoundsException", "Unexpected IndexOutOfBoundsException"

            restartEngine = false;
            showMessage = false;

            boolean isGeneralError = Helpers.startsWithAny(errorContent, "Response code: 429", "Response code: 500");
            if (isGeneralError && isSubtitlesEnabled()) {
                disableSubtitles(); // Response code: 429
            } else if (isGeneralError && getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            } else if (!mBufferingDetector.isPlayable()) { // Response code: 403
                // The stream fails instantly if nParam isn't correct.
                // Note, nParam generation strictly tied to the client but some reported that OkHttp could help.
                YouTubeServiceManager.instance().switchNextClientNow();
                showMessage = true;
            } else {
                YouTubeServiceManager.instance().switchNextClient(); // Response code: 403
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_SUBTITLE) {
            // "Response code: 429" (subtitle error)
            // "Response code: 500" (subtitle error)
            disableSubtitles();
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO) {
            getPlayerData().setTempVideoFormat(FormatItem.VIDEO_FHD_AVC_30);
            if (getPlayerTweaksData().isSWDecoderForced()) {
                getPlayerTweaksData().setSWDecoderForced(false);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO) {
            getPlayerData().setFormat(FormatItem.AUDIO_HQ_MP4A);
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_UNEXPECTED) {
            if (error instanceof NullPointerException) {
                // SABR extractor throws NPE on subtitle error
                // NOTE: the engine should be restarted
                disableSubtitles();
            } else if (Helpers.startsWithAny(errorContent,
                    "Buffer too small", "Invalid to call at Released state; only valid in executing state")) {
                // IllegalStateException: Buffer too small (5242880 < 7208383)
                // NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.
                // This will change current format to 'Disabled'. Do restartEngine() instead.
                lowerVideoQuality(); // NOTE: restart engine required
            }
        }

        if (showMessage) {
            Log.e(TAG, errorMessage);
            String friendly = CarErrors.text(getContext(), error); // car build: no raw error text
            MessageHelpers.showLongMessage(getContext(), friendly);
            if (getPlayer() != null) {
                getPlayer().setTitle(friendly);
            }
        }

        if (restartEngine) {
            mVideoLoaderController.restartEngine();
        } else {
            // Need at least to reload the video because the player becomes idle after error
            mVideoLoaderController.reloadVideo();
        }
    }

    @SuppressLint("StringFormatMatches")
    private String getErrorTitle(int type, int rendererIndex) {
        String errorTitle;
        int msgResId;

        switch (type) {
            // Some ciphered data could be outdated.
            // Might happen when the app wasn't used quite a long time.
            case PlayerEventListener.ERROR_TYPE_SOURCE:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_source;
                        break;
                    default:
                        msgResId = R.string.unknown_source_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_RENDERER:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_renderer;
                        break;
                    default:
                        msgResId = R.string.unknown_renderer_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_UNEXPECTED:
                errorTitle = getContext().getString(R.string.player_unexpected_error);
                break;
            default:
                errorTitle = getContext().getString(R.string.msg_player_error, type);
                break;
        }

        return errorTitle;
    }

    public void runFormatErrorAction(Throwable error) {
        if (getPlayer() == null) {
            return;
        }

        if (isEmbedPlayer()) {
            getPlayer().finish();
            return;
        }

        String message = error.getMessage();
        String className = error.getClass().getSimpleName();
        String fullMsg = String.format("loadFormatInfo error: %s: %s", className, Utils.getStackTraceAsString(error));
        Log.e(TAG, fullMsg);

        if (!Helpers.containsAny(message, "fromNullable result is null")) {
            String friendly = CarErrors.text(getContext(), error); // car build: no raw error text
            MessageHelpers.showLongMessage(getContext(), friendly);
            if (getPlayer() != null) {
                getPlayer().setTitle(friendly);
            }
            CarErrors.whenOnline(getContext(), mResumeWhenOnline);
        }

        if (Utils.fixRetrofitErrors(getContext(), error)) {
            return;
        }

        if (!CarErrors.isOnline(getContext())) {
            return; // car build: reloaded by whenOnline() once the signal is back, not in a loop now
        }

        if (Helpers.containsAny(message, "Unexpected token", "Syntax error", "invalid argument") || // temporal fix
                Helpers.equalsAny(className, "PoTokenException", "BadWebViewException")) {
            YouTubeServiceManager.instance().switchNextClient();
            mVideoLoaderController.reloadVideo();
        } else if (Helpers.containsAny(message, "is not defined")) {
            YouTubeServiceManager.instance().invalidateCache();
            mVideoLoaderController.reloadVideo();
        } else {
            Log.e(TAG, "Probably no internet connection");
            mVideoLoaderController.reloadVideo();
        }
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private void enableFasterDataSource() {
        if (isFasterDataSourceEnabled()) {
            return;
        }

        getPlayerTweaksData().setPlayerDataSource(Utils.getFasterDataSource());
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private boolean isFasterDataSourceEnabled() {
        int fasterDataSource = Utils.getFasterDataSource();
        return getPlayerTweaksData().getPlayerDataSource() == fasterDataSource;
    }

    private void switchNextEngine() {
        getPlayerTweaksData().setPlayerDataSource(getNextEngine());
    }

    private int getNextEngine() {
        int currentEngine = getPlayerTweaksData().getPlayerDataSource();
        Integer[] engineList = Utils.skipCronet() ?
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP } :
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP };
        return Helpers.getNextValue(engineList, currentEngine);
    }

    private boolean isSubtitlesEnabled() {
        return getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(getPlayer().getSubtitleFormat());
    }

    private void disableSubtitles() {
        getPlayerData().setSubtitlesPerChannelEnabled(false); // Important!
        getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }

    private boolean isStreamEnded() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return getVideo().isLiveEnd && getPlayer().getDurationMs() > 0
                && getPlayer().getDurationMs() - getPlayer().getPositionMs() < STREAM_END_THRESHOLD_MS;
    }

    private boolean isOfflineVideo() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return !getVideo().isLive && !getVideo().isLiveEnd;
    }

    /**
     * NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.<br/>
     * This will change current format to 'Disabled'. Do reloadEngine() instead.
     */
    private void lowerVideoQuality() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();

        if (videoFormats == null) {
            return;
        }

        FormatItem videoFormat = getPlayer().getVideoFormat();

        // Limit by 720p
        if (Math.max(videoFormat.getWidth(), videoFormat.getHeight()) <= 1280) {
            return;
        }

        int idx = videoFormats.indexOf(videoFormat);
        int nextIdx = idx + 1;

        if (videoFormats.size() > nextIdx) {
            FormatItem formatItem = videoFormats.get(nextIdx);
            // This helps to persist the format between engine restart
            getPlayerData().setTempVideoFormat(formatItem);
        }
    }
}
