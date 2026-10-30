package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.SimpleExoPlayer;

/**
 * Car build: touch controls on the second screen, like the main screen: tap the video to show them
 * (they hide after 5 s), previous / back 10 s / play-pause / forward 10 s / next, a seek bar, speed,
 * double-tap the left or right side to skip 10 s, swipe in from the right edge for the next videos
 * and from the left edge for the SmartTube menu (also as buttons in the top bar).
 */
final class ScreenTwoControls {
    interface PlayerSource {
        SimpleExoPlayer get();
    }

    /** The side lists of the second screen. */
    interface Panels {
        void openNext();
        void openMenu();
        boolean anyOpen();
        void closeAll();
        void openOptions();  // the main screen's player buttons (quality, subtitles, like, ...)
        void openChannel();  // the channel's latest videos
    }

    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f};

    private static final long HIDE_MS = 5_000;
    private static final long SEEK_MS = 10_000;

    private final Context mCtx;
    private final Activity mActivity; // receives the media keys (same as the main screen's buttons)
    private final PlayerSource mPlayer;
    private final LinearLayout mPanel;
    private final TextView mPlayPause;
    private final SeekBar mSeek;
    private final TextView mTime;
    private final TextView mTitle;
    private final LinearLayout mTop;
    private final TextView mSpeed;
    private final Panels mPanels;
    private float mDownX, mDownY;
    private boolean mSwiped;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mDragging;
    private long mLastTapMs;
    private float mLastTapX;

    private final Runnable mHide = () -> setShown(false);
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            if (mPanel.getVisibility() == View.VISIBLE) {
                mHandler.postDelayed(this, 500);
            }
        }
    };

    ScreenTwoControls(Context ctx, Activity activity, FrameLayout root, PlayerSource player, Panels panels) {
        mCtx = ctx;
        mActivity = activity;
        mPlayer = player;
        mPanels = panels;

        mPanel = new LinearLayout(ctx);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        mPanel.setPadding(dp(24), dp(12), dp(24), dp(16));
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        mPanel.setBackground(shade);
        mPanel.setVisibility(View.GONE);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("|◀◀", 64, v -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        buttons.addView(button("↺ 10", 64, v -> seekBy(-SEEK_MS)));
        mPlayPause = button("❚❚", 80, v -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        buttons.addView(mPlayPause);
        buttons.addView(button("10 ↻", 64, v -> seekBy(SEEK_MS)));
        buttons.addView(button("▶▶|", 64, v -> key(KeyEvent.KEYCODE_MEDIA_NEXT)));
        mPanel.addView(buttons);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mSeek = new SeekBar(ctx);
        mSeek.setMax(1000);
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    showFor();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                mDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                mDragging = false;
                SimpleExoPlayer p = mPlayer.get();
                if (p != null && p.getDuration() > 0) {
                    p.seekTo(p.getDuration() * s.getProgress() / 1000);
                }
                showFor();
            }
        });
        bar.addView(mSeek, new LinearLayout.LayoutParams(0, dp(44), 1));
        mTime = new TextView(ctx);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        mTime.setPadding(dp(12), 0, 0, 0);
        bar.addView(mTime);
        mSpeed = chip("1x", v -> cycleSpeed());
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        spLp.leftMargin = dp(14);
        bar.addView(mSpeed, spLp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(10);
        mPanel.addView(bar, barLp);

        root.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // top bar like the main screen: SmartTube menu, video title, next videos
        mTop = new LinearLayout(ctx);
        mTop.setOrientation(LinearLayout.HORIZONTAL);
        mTop.setGravity(Gravity.CENTER_VERTICAL);
        mTop.setPadding(dp(16), dp(14), dp(16), dp(36));
        mTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));
        mTop.setVisibility(View.GONE);
        mTop.addView(chip("☰  เมนู", v -> {
            setShown(false);
            mPanels.openMenu();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTitle.setPadding(dp(16), 0, dp(16), 0);
        mTop.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        optLp.rightMargin = dp(10);
        mTop.addView(chip("⚙  ตัวเลือก", v -> {
            setShown(false);
            mPanels.openOptions();
        }), optLp);
        mTop.addView(chip("วิดีโอถัดไป  ▤", v -> {
            setShown(false);
            mPanels.openNext();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        root.addView(mTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // tap the video: show / hide; double-tap left or right side: skip 10 s
        // swipe in from the right edge: next videos, from the left edge: SmartTube menu
        root.setOnTouchListener((v, e) -> {
            int w = v.getWidth();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getX();
                    mDownY = e.getY();
                    mSwiped = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - mDownX, dy = Math.abs(e.getY() - mDownY);
                    int h = v.getHeight();
                    // swipe down from the top of the video: the channel's latest videos (like the main screen)
                    if (!mSwiped && !mPanels.anyOpen() && mDownY < h * 0.25f && e.getY() - mDownY > h * 0.18f
                            && e.getY() - mDownY > Math.abs(dx) * 2) {
                        mSwiped = true;
                        setShown(false);
                        mPanels.openChannel();
                        return true;
                    }
                    if (!mSwiped && !mPanels.anyOpen() && Math.abs(dx) > w * 0.12f && Math.abs(dx) > dy * 1.5f) {
                        if (mDownX > w * 0.85f && dx < 0) {
                            mSwiped = true;
                            setShown(false);
                            mPanels.openNext();
                        } else if (mDownX < w * 0.15f && dx > 0) {
                            mSwiped = true;
                            setShown(false);
                            mPanels.openMenu();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    break;
                default:
                    return true;
            }
            if (mSwiped) {
                return true;
            }
            if (mPanels.anyOpen()) { // tap on the video closes the list, like the main screen
                mPanels.closeAll();
                return true;
            }
            long now = System.currentTimeMillis();
            boolean doubleTap = now - mLastTapMs < 350 && Math.abs(e.getX() - mLastTapX) < dp(80);
            mLastTapMs = now;
            mLastTapX = e.getX();
            if (doubleTap) {
                seekBy(e.getX() < v.getWidth() / 2f ? -SEEK_MS : SEEK_MS);
                showFor();
            } else if (mPanel.getVisibility() == View.VISIBLE) {
                setShown(false);
            } else {
                showFor();
            }
            return true;
        });
    }

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }

    private TextView chip(String text, View.OnClickListener click) {
        TextView b = new TextView(mCtx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(18));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            click.onClick(v);
            if (mPanel.getVisibility() == View.VISIBLE) {
                showFor();
            }
        });
        return b;
    }

    private void cycleSpeed() {
        SimpleExoPlayer p = mPlayer.get();
        if (p == null) {
            return;
        }
        float cur = p.getPlaybackParameters().speed;
        int next = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - cur) < 0.01f) {
                next = (i + 1) % SPEEDS.length;
                break;
            }
        }
        p.setPlaybackParameters(new com.google.android.exoplayer2.PlaybackParameters(SPEEDS[next], 1f));
        update();
    }

    private static String speedText(float speed) {
        return (speed == Math.round(speed) ? String.valueOf(Math.round(speed)) : String.valueOf(speed)) + "x";
    }

    private TextView button(String text, int sizeDp, View.OnClickListener click) {
        TextView b = new TextView(mCtx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp * 0.32f));
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x88000000);
        bg.setStroke(dp(1.5f), 0x66FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            click.onClick(v);
            showFor();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = lp.rightMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    void showFor() {
        setShown(true);
        mHandler.removeCallbacks(mHide);
        mHandler.postDelayed(mHide, HIDE_MS);
    }

    private void setShown(boolean shown) {
        mPanel.setVisibility(shown ? View.VISIBLE : View.GONE);
        mTop.setVisibility(shown ? View.VISIBLE : View.GONE);
        mHandler.removeCallbacks(mTick);
        if (shown) {
            mHandler.post(mTick);
        }
    }

    void release() {
        mHandler.removeCallbacksAndMessages(null);
    }

    private void update() {
        SimpleExoPlayer p = mPlayer.get();
        if (p == null) {
            return;
        }
        mPlayPause.setText(p.getPlayWhenReady() ? "❚❚" : "▶");
        mSpeed.setText(speedText(p.getPlaybackParameters().speed));
        try {
            com.liskovsoft.smartyoutubetv2.common.app.models.data.Video v =
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(mActivity).getVideo();
            String title = v != null ? v.getTitle() : null;
            if (title != null && !title.contentEquals(mTitle.getText())) {
                mTitle.setText(title);
            }
        } catch (Throwable ignored) {
        }
        long dur = p.getDuration(), pos = p.getCurrentPosition();
        if (dur > 0 && !mDragging) {
            mSeek.setProgress((int) (pos * 1000 / dur));
        }
        mTime.setText(fmt(pos) + " / " + (dur > 0 ? fmt(dur) : "--:--"));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    private void seekBy(long delta) {
        SimpleExoPlayer p = mPlayer.get();
        if (p == null) {
            return;
        }
        long dur = p.getDuration();
        long pos = Math.max(0, p.getCurrentPosition() + delta);
        if (dur > 0) {
            pos = Math.min(pos, dur - 1000);
        }
        p.seekTo(pos);
        update();
    }

    private void key(int code) {
        mActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        mActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        mHandler.postDelayed(this::update, 300);
    }
}
