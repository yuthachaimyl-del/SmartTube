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
 * (they hide after 5 s), previous / back 10 s / play-pause / forward 10 s / next, a seek bar,
 * and double-tap the left or right side to skip 10 s.
 */
final class ScreenTwoControls {
    interface PlayerSource {
        SimpleExoPlayer get();
    }

    private static final long HIDE_MS = 5_000;
    private static final long SEEK_MS = 10_000;

    private final Context mCtx;
    private final Activity mActivity; // receives the media keys (same as the main screen's buttons)
    private final PlayerSource mPlayer;
    private final LinearLayout mPanel;
    private final TextView mPlayPause;
    private final SeekBar mSeek;
    private final TextView mTime;
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

    ScreenTwoControls(Context ctx, Activity activity, FrameLayout root, PlayerSource player) {
        mCtx = ctx;
        mActivity = activity;
        mPlayer = player;

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
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(10);
        mPanel.addView(bar, barLp);

        root.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // tap the video: show / hide; double-tap left or right side: skip 10 s
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) {
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
