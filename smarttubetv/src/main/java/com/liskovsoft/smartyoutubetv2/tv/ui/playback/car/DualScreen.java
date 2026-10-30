package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.video.VideoListener;

/**
 * Car build: play the video on the second screen while the main screen keeps the controls
 * and the suggestions (no black video box). Toggled by the "จอ 2" button in the player.
 * Stays on for the next videos until it is switched off.
 */
public final class DualScreen {
    private static boolean sEnabled;

    /** What the second screen can pick from (same lists as the main screen). */
    public interface Library {
        java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> getSuggestions();
        void play(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video);
    }

    private Library mLibrary;

    public void setLibrary(Library library) {
        mLibrary = library;
    }

    private final Activity mActivity;
    private final ViewGroup mRoot;           // player fragment root
    private final View mMainVideo;           // main screen SurfaceView / TextureView
    private SimpleExoPlayer mPlayer;
    private TextView mButton;
    private TextView mPlaceholder;
    private VideoPresentation mPresentation;
    private boolean mStarted;

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { refresh(); }
        @Override public void onDisplayRemoved(int id) { refresh(); }
        @Override public void onDisplayChanged(int id) { }
    };

    private final VideoListener mVideoListener = new VideoListener() {
        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            if (mPresentation != null && height > 0) {
                mPresentation.setAspect(width * pixelWidthHeightRatio / height);
            }
        }
    };

    public DualScreen(Activity activity, ViewGroup fragmentRoot, View mainVideo) {
        mActivity = activity;
        mRoot = fragmentRoot;
        mMainVideo = mainVideo;
        addButton();
        addPlaceholder();
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    private Display secondDisplay() {
        DisplayManager dm = (DisplayManager) mActivity.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (pres != null && pres.length > 0) {
            return pres[0];
        }
        int mine = mActivity.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != mine && d.getState() != Display.STATE_OFF) {
                return d;
            }
        }
        return null;
    }

    private void addButton() {
        mButton = new TextView(mActivity);
        mButton.setTextColor(Color.WHITE);
        mButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        mButton.setGravity(Gravity.CENTER);
        mButton.setPadding(dp(18), 0, dp(18), 0);
        mButton.setFocusable(false);
        mButton.setOnClickListener(v -> {
            sEnabled = !sEnabled;
            refresh();
        });
        // how the picture fills the second screen: zoom (crop) / stretch / fit
        sScaleMode = mActivity.getSharedPreferences("car_dual", Context.MODE_PRIVATE).getInt("scale", SCALE_ZOOM);
        mScaleButton = new TextView(mActivity);
        mScaleButton.setTextColor(Color.WHITE);
        mScaleButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(20));
        mScaleButton.setGravity(Gravity.CENTER);
        mScaleButton.setPadding(dp(16), 0, dp(16), 0);
        mScaleButton.setFocusable(false);
        mScaleButton.setOnClickListener(v -> {
            sScaleMode = (sScaleMode + 1) % 3;
            mActivity.getSharedPreferences("car_dual", Context.MODE_PRIVATE).edit().putInt("scale", sScaleMode).apply();
            styleButton();
            if (mPresentation != null) {
                mPresentation.applyScale();
            }
        });

        android.widget.LinearLayout row = new android.widget.LinearLayout(mActivity);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams blp =
                new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64));
        blp.rightMargin = dp(10);
        row.addView(mScaleButton, blp);
        row.addView(mButton, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64),
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(84); // left of the on-screen Back button
        row.setElevation(dp(20));
        mButtonRow = row;
        mRoot.addView(row, lp);
        styleButton();
    }

    static final int SCALE_ZOOM = 0, SCALE_STRETCH = 1, SCALE_FIT = 2;
    private static int sScaleMode = SCALE_ZOOM;
    private TextView mScaleButton;
    private View mButtonRow;
    private boolean mHasSecond;
    private boolean mButtonsWanted = true;

    /** Auto-hide: the buttons show only for a few seconds after touching the screen. */
    public void setButtonsVisible(boolean visible) {
        mButtonsWanted = visible;
        int v = mHasSecond && visible ? View.VISIBLE : View.GONE;
        if (mButtonRow.getVisibility() != v) {
            mButtonRow.setVisibility(v);
        }
    }

    private void styleButton() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(32));
        bg.setColor(sEnabled ? 0xDDCC2222 : 0x99000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        mButton.setBackground(bg);
        mButton.setText(sEnabled ? "จอ 2 ●" : "จอ 2");
        if (mScaleButton != null) {
            GradientDrawable sbg = new GradientDrawable();
            sbg.setCornerRadius(dp(32));
            sbg.setColor(0x99000000);
            sbg.setStroke(dp(1), 0x66FFFFFF);
            mScaleButton.setBackground(sbg);
            mScaleButton.setText(sScaleMode == SCALE_ZOOM ? "⛶ เต็มจอ (ซูม)" : sScaleMode == SCALE_STRETCH ? "⛶ เต็มจอ (ยืด)" : "▭ พอดีจอ");
            mScaleButton.setVisibility(sEnabled ? View.VISIBLE : View.GONE);
        }
    }

    private void addPlaceholder() {
        mPlaceholder = new TextView(mActivity);
        mPlaceholder.setText(""); // controls stay on screen in this mode, just a black backdrop
        mPlaceholder.setTextColor(0xCCFFFFFF);
        mPlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        mPlaceholder.setGravity(Gravity.CENTER);
        mPlaceholder.setBackgroundColor(Color.BLACK); // hides the last frame left on the main screen
        mPlaceholder.setVisibility(View.GONE);
        mPlaceholder.setClickable(false);
        mRoot.addView(mPlaceholder, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    /** New player instance (it is recreated on every start). */
    public void setPlayer(SimpleExoPlayer player) {
        if (mPlayer != null) {
            mPlayer.removeVideoListener(mVideoListener);
        }
        mPlayer = player;
        if (mPlayer != null) {
            mPlayer.addVideoListener(mVideoListener);
            Surface override = LeanbackPlayerAdapter.sOverrideSurface;
            if (mPresentation != null && override != null && override.isValid()) { // mirror input or screen 2
                bindSurface(override);
            }
        }
        refresh();
    }

    public void onStart() {
        mStarted = true;
        DisplayManager dm = (DisplayManager) mActivity.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            dm.registerDisplayListener(mDisplayListener, null);
        }
        refresh();
    }

    public void onStop() {
        mStarted = false;
        DisplayManager dm = (DisplayManager) mActivity.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            dm.unregisterDisplayListener(mDisplayListener);
        }
        refresh();
    }

    /** Video only on the second screen (main screen = remote). False while mirroring to both screens. */
    public static boolean isActive() {
        return sEnabled && LeanbackPlayerAdapter.sOverrideSurface != null && !sMirroring;
    }

    // ---- both screens at once: one decoder, GPU copy to the main and the second screen

    private static volatile boolean sMirroring;
    private MirrorRenderer mMirror;
    private Surface mMainTarget;
    private Surface mPresentationTarget;
    private boolean mMainCallbackAdded;
    private boolean mSecondOnly;     // no OpenGL: video on screen 2 only, main screen = remote
    private boolean mMirrorStarting;
    private int mMirrorGen;

    private Surface mainSurface() {
        if (mMainVideo instanceof SurfaceView) {
            Surface hs = ((SurfaceView) mMainVideo).getHolder().getSurface();
            return hs != null && hs.isValid() ? hs : null;
        }
        return null;
    }

    /** Keeps both screens drawn: (re)attaches a screen whose surface isn't in the mirror yet. */
    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mMirror == null || !sMirroring) {
                return;
            }
            boolean missing = false;
            Surface main = mainSurface();
            if (main != null && !mMirror.hasTarget(main)) {
                mMainTarget = main;
                mMirror.addTarget(main);
                missing = true;
            }
            if (mPresentationTarget != null && mPresentationTarget.isValid() && !mMirror.hasTarget(mPresentationTarget)) {
                mMirror.addTarget(mPresentationTarget);
                missing = true;
            }
            Surface input = mMirror.getInputSurface();
            if (input != null && LeanbackPlayerAdapter.sOverrideSurface != input) {
                LeanbackPlayerAdapter.sOverrideSurface = input;
                bindSurface(input);
            }
            mMainVideo.postDelayed(this, missing ? 300 : 1000);
        }
    };

    private void startMirror(Surface presentation) {
        mPresentationTarget = presentation;
        if (mMirror != null) {
            attachMirror();
            return;
        }
        if (mMirrorStarting) {
            return;
        }
        mMirrorStarting = true;
        mSecondOnly = false;
        mPlaceholder.setVisibility(View.GONE); // the main screen keeps its own picture until the mirror is up
        final int gen = ++mMirrorGen;
        new MirrorRenderer((renderer, ok) -> {
            if (gen != mMirrorGen || mPresentation == null || mPresentationTarget == null
                    || !mPresentationTarget.isValid()) {
                renderer.release(); // switched off meanwhile
                return;
            }
            mMirrorStarting = false;
            if (!ok) { // no OpenGL: second screen only
                renderer.release();
                sMirroring = false;
                mSecondOnly = true;
                LeanbackPlayerAdapter.sOverrideSurface = mPresentationTarget;
                bindSurface(mPresentationTarget);
                mPlaceholder.setVisibility(View.VISIBLE);
                return;
            }
            mMirror = renderer;
            attachMirror();
        });
    }

    private void attachMirror() {
        Surface input = mMirror.getInputSurface();
        sMirroring = true;
        mSecondOnly = false;
        mMirror.addTarget(mPresentationTarget);
        LeanbackPlayerAdapter.sOverrideSurface = input;
        bindSurface(input); // the decoder lets go of the main surface here...
        if (!mMainCallbackAdded && mMainVideo instanceof SurfaceView) {
            mMainCallbackAdded = true;
            ((SurfaceView) mMainVideo).getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    if (mMirror != null && sMirroring) {
                        mMainTarget = h.getSurface();
                        mMirror.addTarget(mMainTarget);
                    }
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    if (mMirror != null && mMirror.hasTarget(h.getSurface())) {
                        mMirror.removeTarget(h.getSurface());
                    }
                    mMainTarget = null;
                }
            });
        }
        mPlaceholder.setVisibility(View.GONE); // the main screen keeps showing the video
        // ...so the watchdog can hand it to the mirror a moment later (and keeps it there)
        mMainVideo.removeCallbacks(mWatchdog);
        mMainVideo.postDelayed(mWatchdog, 150);
    }

    /** Video back to the main screen only. Call with sOverrideSurface already cleared. */
    private void stopMirror() {
        mMirrorGen++;
        mMirrorStarting = false;
        sMirroring = false;
        mSecondOnly = false;
        mMainVideo.removeCallbacks(mWatchdog);
        MirrorRenderer m = mMirror;
        mMirror = null;
        if (m != null) {
            Surface main = mainSurface();
            if (main != null) {
                m.removeTarget(main); // free the main surface first...
            }
        }
        bindMainSurface();            // ...so the decoder can take it back...
        if (m != null) {
            m.release();              // ...before the mirror input goes away
        }
        mMainTarget = null;
        mPresentationTarget = null;
    }

    private void refresh() {
        Display second = secondDisplay();
        mHasSecond = second != null;
        mButtonRow.setVisibility(mHasSecond && mButtonsWanted ? View.VISIBLE : View.GONE);
        styleButton();
        boolean want = sEnabled && mStarted && second != null && !mActivity.isFinishing();
        if (want && mPresentation == null) {
            show(second);
        } else if (!want && mPresentation != null) {
            hide();
        }
        mPlaceholder.setVisibility(mPresentation != null && mSecondOnly ? View.VISIBLE : View.GONE);
    }

    private void show(Display display) {
        try {
            mPresentation = new VideoPresentation(mActivity, display);
            mPresentation.show();
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            display.getRealMetrics(m);
            android.widget.Toast.makeText(mActivity, "จอ 2: " + display.getName() + "  " + m.widthPixels + "×"
                    + m.heightPixels + "  " + m.densityDpi + "dpi", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            mPresentation = null;
            sEnabled = false;
            styleButton();
            android.widget.Toast.makeText(mActivity, "จอ 2 ไม่ยอมให้แสดงวิดีโอ", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void hide() {
        LeanbackPlayerAdapter.sOverrideSurface = null;
        stopMirror(); // also gives the video back to the main screen
        try {
            mPresentation.dismiss();
        } catch (Exception ignored) {
        }
        mPresentation = null;
    }

    private void bindSurface(Surface surface) {
        if (mPlayer != null) {
            mPlayer.setVideoSurface(surface);
        }
    }

    private void bindMainSurface() {
        if (mPlayer == null) {
            return;
        }
        Surface s = null;
        if (mMainVideo instanceof SurfaceView) {
            Surface hs = ((SurfaceView) mMainVideo).getHolder().getSurface();
            if (hs != null && hs.isValid()) {
                s = hs;
            }
        } else if (mMainVideo instanceof TextureView && ((TextureView) mMainVideo).getSurfaceTexture() != null) {
            s = new Surface(((TextureView) mMainVideo).getSurfaceTexture());
        }
        mPlayer.setVideoSurface(s);
    }

    private final class VideoPresentation extends Presentation {
        private AspectRatioFrameLayout mFrame;
        private FrameLayout mRootView;
        private ScreenTwoControls mControls;
        private SidePanel mNext;
        private SidePanel mMenu;
        private io.reactivex.disposables.Disposable mMenuLoad;
        private io.reactivex.disposables.Disposable mNextSearch;
        private int mMenuIndex = -1;

        @Override
        protected void onStop() {
            if (mControls != null) {
                mControls.release();
            }
            if (mMenuLoad != null) {
                mMenuLoad.dispose();
            }
            if (mNextSearch != null) {
                mNextSearch.dispose();
            }
            super.onStop();
        }

        private void buildPanels() {
            SidePanel.Host nextHost = new SidePanel.Host() {
                @Override public java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> getSuggestions() {
                    return mLibrary != null ? mLibrary.getSuggestions() : null;
                }
                @Override public void onVideoClicked(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video) {
                    if (mLibrary != null) {
                        mLibrary.play(video);
                    }
                }
                @Override public void setVideoFraction(float fraction, boolean videoOnRight) {
                    VideoPresentation.this.setVideoFraction(fraction, videoOnRight);
                }
            };
            SidePanel.Host menuHost = new SidePanel.Host() {
                @Override public java.util.List<com.liskovsoft.smartyoutubetv2.common.app.models.data.Video> getSuggestions() {
                    return null;
                }
                @Override public void onVideoClicked(com.liskovsoft.smartyoutubetv2.common.app.models.data.Video video) {
                    nextHost.onVideoClicked(video);
                }
                @Override public void setVideoFraction(float fraction, boolean videoOnRight) {
                    VideoPresentation.this.setVideoFraction(fraction, videoOnRight);
                }
            };
            mNext = new SidePanel(mActivity, getContext(), mRootView, nextHost, false, "วิดีโอถัดไป", null);
            mNext.setSearch((query, panel) -> {
                if (mNextSearch != null) {
                    mNextSearch.dispose();
                }
                mNextSearch = com.liskovsoft.smartyoutubetv2.common.car.CarSections.search(query, (videos, error) -> {
                    if (mNext != null) {
                        mNext.setItems(videos, error != null ? error : "ไม่พบวิดีโอ");
                    }
                });
            });
            mMenu = new SidePanel(mActivity, getContext(), mRootView, menuHost, true, "PiloTube",
                    new SidePanel.Tabs() {
                        @Override public String[] titles() {
                            return com.liskovsoft.smartyoutubetv2.common.car.CarSections.TITLES;
                        }
                        @Override public void onTabSelected(int index, SidePanel panel) {
                            loadMenu(index);
                        }
                    });
            mMenu.setSearch((query, panel) -> {
                if (mMenuLoad != null) {
                    mMenuLoad.dispose();
                }
                mMenuIndex = -2;
                mMenuLoad = com.liskovsoft.smartyoutubetv2.common.car.CarSections.search(query, (videos, error) -> {
                    if (mMenu != null && mMenuIndex == -2) {
                        mMenu.setItems(videos, error != null ? error : "ไม่พบวิดีโอ");
                    }
                });
            });
        }

        private void loadMenu(int index) {
            if (mMenuLoad != null) {
                mMenuLoad.dispose();
            }
            mMenuIndex = index;
            mMenu.setItems(null, "กำลังโหลด…");
            mMenuLoad = com.liskovsoft.smartyoutubetv2.common.car.CarSections.load(index, (videos, error) -> {
                if (mMenu != null && mMenuIndex == index) {
                    mMenu.setItems(videos, error != null ? error : "ไม่มีวิดีโอ");
                }
            });
        }

        private final ScreenTwoControls.Panels mPanels = new ScreenTwoControls.Panels() {
            @Override public void openNext() {
                if (mMenu.isOpen()) {
                    mMenu.close();
                }
                mNext.open();
            }
            @Override public void openMenu() {
                if (mNext.isOpen()) {
                    mNext.close();
                }
                mMenu.open();
                if (mMenuIndex == -1) {
                    loadMenu(0);
                }
            }
            @Override public boolean anyOpen() {
                return mNext.isOpen() || mMenu.isOpen();
            }
            @Override public void closeAll() {
                mNext.close();
                mMenu.close();
            }
        };

        /** A list is open: the video moves to one side (fit), back to the full screen when it closes. */
        void setVideoFraction(float fraction, boolean videoOnRight) {
            if (mFrame == null || mRootView == null) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mFrame.getLayoutParams();
            if (fraction >= 1f) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.CENTER;
                applyScale();
            } else {
                lp.width = Math.round(mRootView.getWidth() * fraction);
                lp.gravity = (videoOnRight ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
                mFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
            mFrame.setLayoutParams(lp);
        }

        VideoPresentation(Context outer, Display display) {
            super(outer, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            mRootView = root;
            root.setBackgroundColor(Color.BLACK);
            mFrame = new AspectRatioFrameLayout(getContext());
            applyScale();
            SurfaceView sv = new SurfaceView(getContext());
            mFrame.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(mFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            setContentView(root);
            buildPanels();
            mControls = new ScreenTwoControls(getContext(), mActivity, root, () -> mPlayer, mPanels);
            mControls.showFor(); // show once so it's clear the second screen can be touched
            if (mPlayer != null) {
                Format f = mPlayer.getVideoFormat();
                if (f != null && f.height > 0) {
                    setAspect(f.width * (f.pixelWidthHeightRatio > 0 ? f.pixelWidthHeightRatio : 1f) / f.height);
                } else {
                    setAspect(16f / 9f);
                }
            }
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    startMirror(holder.getSurface());
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    if (holder.getSurface() == mPresentationTarget || LeanbackPlayerAdapter.sOverrideSurface == holder.getSurface()) {
                        LeanbackPlayerAdapter.sOverrideSurface = null;
                        stopMirror(); // also gives the video back to the main screen
                    }
                }
            });
        }

        void applyScale() {
            if (mFrame != null && (mFrame.getLayoutParams() == null
                    || mFrame.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT)) { // not while a list is open
                mFrame.setResizeMode(sScaleMode == SCALE_ZOOM ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : sScaleMode == SCALE_STRETCH ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                        : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
        }

        void setAspect(float aspect) {
            if (mFrame != null && aspect > 0) {
                mFrame.setAspectRatio(aspect);
            }
        }
    }
}
