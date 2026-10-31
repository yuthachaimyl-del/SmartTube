package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.leanback.app.PlaybackSupportFragment;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerEngine;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * Subclass of {@link PlaybackSupportFragment} that is responsible for providing a {@link SurfaceView}
 * and rendering video.
 */
public class SurfacePlaybackFragment extends PlaybackSupportFragment {
    private SurfaceWrapper mVideoSurfaceWrapper;
    private AspectRatioFrameLayout mVideoSurfaceRoot;
    private SubtitleView mLeanbackSubtitles;
    private int mSubtitlesPadding;
    private int mBackgroundResId;
    private float mAspectRatio;
    private float mPixelRatio = 1.0f;
    private float mVideoAspectRatio;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup root = (ViewGroup) super.onCreateView(inflater, container, savedInstanceState);
        if (root == null) {
            throw new IllegalStateException("Can't create root of SurfacePlaybackFragment");
        }
        mVideoSurfaceWrapper = (PlayerTweaksData.instance(getContext()).isTextureViewEnabled() ||
                PlayerData.instance(getContext()).getRotationAngle() != 0) ?
                new TextureViewWrapper(getContext(), root) : new SurfaceViewWrapper(getContext(), root);
        mVideoSurfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
        mVideoSurfaceRoot.setAspectRatioListener((targetAspectRatio, naturalAspectRatio, aspectRatioMismatch) -> scaleIfNeeded());
        mLeanbackSubtitles = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.leanback_subtitles);
        mSubtitlesPadding = mLeanbackSubtitles.getPaddingLeft();
        setBackgroundType(PlaybackSupportFragment.BG_LIGHT);
        return root;
    }

    /**
     * Adds {@link SurfaceHolder.Callback} to {@link SurfaceView}.
     */
    public void setSurfaceHolderCallback(SurfaceHolder.Callback callback) {
        if (mVideoSurfaceWrapper != null) {
            mVideoSurfaceWrapper.setSurfaceHolderCallback(callback);
        }
    }

    @Override
    protected void onVideoSizeChanged(int width, int height) {
        mVideoAspectRatio = ((float) width) / height;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());
    }

    /**
     * Returns the surface view.
     */
    public View getSurfaceView() {
        return mVideoSurfaceWrapper.getSurfaceView();
    }

    @Override
    public void onDestroyView() {
        mVideoSurfaceWrapper = null;
        super.onDestroyView();
    }

    /** Returns the {@link ResizeMode}. */
    protected @ResizeMode int getResize() {
        return mVideoSurfaceRoot.getResizeMode();
    }

    /**
     * Sets the {@link ResizeMode}.
     *
     * @param resizeMode The {@link ResizeMode}.
     */
    protected void setResize(@ResizeMode int resizeMode) {
        mVideoSurfaceRoot.setResizeMode(resizeMode);
    }

    protected void setZoom(int percents) {
        mVideoSurfaceRoot.setZoom(percents);
    }

    protected void setAspect(float aspectRatio) {
        mAspectRatio = aspectRatio;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());
    }

    protected void setRotation(int angle) {
        if (Helpers.floatEquals(mVideoSurfaceRoot.getRotation(), angle) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setRotation(angle);
        } else {
            mVideoSurfaceRoot.removeView(mVideoSurfaceWrapper.getSurfaceView());
            mVideoSurfaceWrapper = new TextureViewWrapper(getContext(), (ViewGroup) getView());
            mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
            mVideoSurfaceRoot.setRotation(angle);

            ((PlayerEngine) this).restartEngine();
        }
    }

    protected void setFlipEnabled(boolean enabled) {
        float scaleX = enabled ? -1f : 1f;

        if (Helpers.floatEquals(mVideoSurfaceRoot.getScaleX(), scaleX) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setScaleX(scaleX);
        } else {
            mVideoSurfaceRoot.removeView(mVideoSurfaceWrapper.getSurfaceView());
            mVideoSurfaceWrapper = new TextureViewWrapper(getContext(), (ViewGroup) getView());
            mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
            mVideoSurfaceRoot.setScaleX(scaleX);

            ((PlayerEngine) this).restartEngine();
        }
    }

    private void scaleIfNeeded() {
        if (!(mVideoSurfaceWrapper instanceof TextureViewWrapper)) {
            return;
        }

        if (mVideoSurfaceRoot.getWidth() == 0 || mVideoSurfaceRoot.getHeight() == 0) {
            return;
        }

        float angle = mVideoSurfaceRoot.getRotation();

        int width, height;

        if (Helpers.floatEquals(angle, 90) || Helpers.floatEquals(angle, 270)) {
            float ratio = mVideoSurfaceRoot.getWidth() / ((float) mVideoSurfaceRoot.getHeight());

            width = mVideoSurfaceRoot.getHeight();
            height = (int) (mVideoSurfaceRoot.getHeight() / ratio);
        } else {
            width = mVideoSurfaceRoot.getWidth();
            height = mVideoSurfaceRoot.getHeight();
        }

        // https://stackoverflow.com/questions/52196362/how-resize-textureview-to-fullscreen-when-rotation-90
        View textureView = mVideoSurfaceWrapper.getSurfaceView();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        textureView.setLayoutParams(params);
    }

    protected void setPixelRatio(float pixelRatio) {
        mPixelRatio = pixelRatio;
    }

    /**
     * Setup player's background used when controls are showed.
     * @param resId background
     */
    protected void setBackgroundResource(int resId) {
        if (resId <= 0 || mBackgroundResId == resId) {
            return;
        }

        View backgroundView = (View) Helpers.getField(this, "mBackgroundView");

        if (backgroundView != null) {
            backgroundView.setBackgroundResource(resId);
            mBackgroundResId = resId;
        }
    }

    protected void setGravity(int gravity) {
        ViewUtil.setGravity(mVideoSurfaceRoot, gravity);

        scaleSubtitles(gravity);
    }

    private void scaleSubtitles(int gravity) {
        if ((gravity & Gravity.START) == Gravity.START) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.START);
        } else if ((gravity & Gravity.END) == Gravity.END) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.END);
        } else if ((gravity & Gravity.CENTER) == Gravity.CENTER) {
            scaleSubtitles(ViewGroup.LayoutParams.MATCH_PARENT, mSubtitlesPadding, Gravity.CENTER);
        }
    }

    private void scaleSubtitles(int width, int padding, int gravity) {
        ViewUtil.setWidth(mLeanbackSubtitles, width);
        ViewUtil.setPadding(mLeanbackSubtitles, padding);
        ViewUtil.setGravity(mLeanbackSubtitles, gravity);
    }

    private int scaleSubsWidth() {
        View parent = (View) mLeanbackSubtitles.getParent();
        return parent.getWidth() / 100 * calculateZoom();
    }

    private int scaleSubsPadding() {
        return mSubtitlesPadding / 100 * calculateZoom();
    }

    private int calculateZoom() {
        View parent = (View) mLeanbackSubtitles.getParent();
        int widthRatio = mVideoSurfaceRoot.getWidth() * 100 / parent.getWidth();
        return mVideoSurfaceRoot.getZoom() * widthRatio / 100;
    }

    // ---- Car build: suggestions in a right-side panel instead of covering the video

    /** Part of the screen width the video keeps while the suggestions panel is open. */
    private static final float SIDE_VIDEO_FRACTION = 0.62f;
    private static final int SIDE_PANEL_COLOR = 0xEE101010;
    private boolean mSideMode;

    /** Only on wide landscape screens (car head units, e.g. 1920x720); normal TVs keep the original overlay. */
    protected boolean isSidePanelScreen() {
        View root = getView();
        return root != null && root.getHeight() > 0 && root.getWidth() / (float) root.getHeight() >= 1.9f;
    }

    /**
     * Shrinks the video to the left and moves the controls/suggestions dock to the right,
     * so the suggestions no longer cover the picture. {@code false} restores full screen.
     */
    protected void setSideMode(boolean enabled) {
        View root = getView();
        if (root == null || mVideoSurfaceRoot == null || mSideMode == enabled) {
            return;
        }
        mSideMode = enabled;

        int videoWidth = Math.round(root.getWidth() * SIDE_VIDEO_FRACTION);

        FrameLayout.LayoutParams videoParams = (FrameLayout.LayoutParams) mVideoSurfaceRoot.getLayoutParams();
        videoParams.width = enabled ? videoWidth : ViewGroup.LayoutParams.MATCH_PARENT;
        videoParams.gravity = enabled ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER;
        mVideoSurfaceRoot.setLayoutParams(videoParams);

        scaleSubtitles(enabled ? videoWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                enabled ? mSubtitlesPadding / 3 : mSubtitlesPadding,
                enabled ? Gravity.START : Gravity.CENTER);

        View dock = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.playback_controls_dock);
        if (dock != null) {
            FrameLayout.LayoutParams dockParams = (FrameLayout.LayoutParams) dock.getLayoutParams();
            dockParams.leftMargin = enabled ? videoWidth : 0;
            dock.setLayoutParams(dockParams);
            dock.setBackgroundColor(enabled ? SIDE_PANEL_COLOR : 0);
        }
    }

    protected boolean isSideMode() {
        return mSideMode;
    }

    /** Car build: video keeps only the left part of the screen (swipe-in list on the right). 1 = full width. */
    public void setVideoFraction(float fraction, boolean videoOnRight) {
        View root = getView();
        if (root == null || mVideoSurfaceRoot == null) {
            return;
        }
        boolean full = fraction >= 1f;
        int videoWidth = Math.round(root.getWidth() * fraction);
        FrameLayout.LayoutParams videoParams = (FrameLayout.LayoutParams) mVideoSurfaceRoot.getLayoutParams();
        videoParams.width = full ? ViewGroup.LayoutParams.MATCH_PARENT : videoWidth;
        int side = videoOnRight ? Gravity.END : Gravity.START;
        videoParams.gravity = full ? Gravity.CENTER : (side | Gravity.CENTER_VERTICAL);
        mVideoSurfaceRoot.setLayoutParams(videoParams);
        scaleSubtitles(full ? ViewGroup.LayoutParams.MATCH_PARENT : videoWidth,
                full ? mSubtitlesPadding : mSubtitlesPadding / 3, full ? Gravity.CENTER : side);
    }

    /**
     * Car build: the video keeps this part of the screen while a list is open (beside it on wide
     * windows, above it on narrow ones); null = full screen again. Subtitles follow the video.
     */
    public void setVideoArea(android.graphics.Rect area) {
        View root = getView();
        if (root == null || mVideoSurfaceRoot == null) {
            return;
        }
        // Car build: the video sits in the middle of the area next to the open list (was: in its top corner)
        com.liskovsoft.smartyoutubetv2.tv.ui.playback.car.SidePanel.placeInArea(mVideoSurfaceRoot, area);

        if (mLeanbackSubtitles != null && mLeanbackSubtitles.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams subsParams = (FrameLayout.LayoutParams) mLeanbackSubtitles.getLayoutParams();
            subsParams.width = area == null ? ViewGroup.LayoutParams.MATCH_PARENT : area.width();
            subsParams.height = area == null ? ViewGroup.LayoutParams.MATCH_PARENT : area.height();
            subsParams.leftMargin = area == null ? 0 : area.left;
            subsParams.topMargin = area == null ? 0 : area.top;
            subsParams.gravity = area == null ? Gravity.CENTER : Gravity.TOP | Gravity.START;
            mLeanbackSubtitles.setLayoutParams(subsParams);
            ViewUtil.setPadding(mLeanbackSubtitles, area == null ? mSubtitlesPadding : mSubtitlesPadding / 3);
        }
    }

    private float calculateAspectRatio() {
        return (mAspectRatio == 0 ? mVideoAspectRatio : mAspectRatio) * mPixelRatio;
    }
}
