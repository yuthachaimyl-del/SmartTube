package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.SimpleExoPlayer;

/**
 * Car build: YouTube-app style buttons in the middle of the video while the controls are shown:
 * previous video, back 10 s, play/pause, forward 10 s, next video. Big enough for a finger.
 */
public final class CenterControls {
    public interface Host {
        boolean isControlsShown();
        SimpleExoPlayer getPlayer();
        void onUserAction(); // keep the controls on screen a bit longer
        void onSync();       // called a few times a second (auto-hide of the floating buttons)
    }

    private static final long SEEK_MS = 10_000;

    private final Activity mActivity;
    private final Host mHost;
    private final LinearLayout mBar;
    private final TextView mPlayPause;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mStopped;

    private final Runnable mSync = new Runnable() {
        @Override
        public void run() {
            if (mStopped) {
                return;
            }
            sync();
            mHandler.postDelayed(this, 250);
        }
    };

    public CenterControls(Activity activity, ViewGroup root, Host host) {
        mActivity = activity;
        mHost = host;
        mBar = new LinearLayout(activity);
        mBar.setOrientation(LinearLayout.HORIZONTAL);
        mBar.setGravity(Gravity.CENTER);
        mBar.setVisibility(View.GONE);
        mBar.addView(button("|◀◀", 76, v -> sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        mBar.addView(button("↺ 10", 76, v -> seekBy(-SEEK_MS)));
        mPlayPause = button("❚❚", 96, v -> playPause());
        mBar.addView(mPlayPause);
        mBar.addView(button("10 ↻", 76, v -> seekBy(SEEK_MS)));
        mBar.addView(button("▶▶|", 76, v -> next()));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(70);
        mBar.setElevation(dp(16));
        root.addView(mBar, lp);
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    private TextView button(String text, int sizeDp, View.OnClickListener click) {
        TextView b = new TextView(mActivity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp * 0.32f));
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x88000000);
        bg.setStroke(dp(1.5f), 0x66FFFFFF);
        b.setBackground(bg);
        b.setFocusable(false);
        b.setOnClickListener(v -> {
            click.onClick(v);
            mHost.onUserAction();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = lp.rightMargin = dp(22);
        b.setLayoutParams(lp);
        return b;
    }

    public void start() {
        mStopped = false;
        mHandler.removeCallbacks(mSync);
        mHandler.post(mSync);
    }

    public void stop() {
        mStopped = true;
        mHandler.removeCallbacks(mSync);
    }

    private void sync() {
        mHost.onSync();
        // second-screen mode: the main screen is the remote, keep the controls on screen
        boolean keep = DualScreen.isActive();
        com.liskovsoft.smartyoutubetv2.common.car.CarState.sKeepControls = keep;
        if (keep && !mHost.isControlsShown() && mHost.getPlayer() != null) {
            mHost.onUserAction();
        }
        boolean show = mHost.isControlsShown() && mHost.getPlayer() != null;
        mBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            mPlayPause.setText(mHost.getPlayer().getPlayWhenReady() ? "❚❚" : "▶");
        }
    }

    private void seekBy(long deltaMs) {
        SimpleExoPlayer p = mHost.getPlayer();
        if (p == null) {
            return;
        }
        long dur = p.getDuration();
        long pos = Math.max(0, p.getCurrentPosition() + deltaMs);
        if (dur > 0) {
            pos = Math.min(pos, dur - 1000);
        }
        p.seekTo(pos);
    }

    private void playPause() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    private void next() {
        sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private void sendKey(int code) {
        mActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        mActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
}
