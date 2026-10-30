package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

/**
 * Car build: after Back from a playing video it keeps playing behind the app's pages. This bar at the top
 * of those pages (bottom, clear of the search row) shows it: tap to go back to the video, ✕ to stop it.
 */
public final class NowPlayingBar {
    private static final String TAG = "car_now_playing";
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Runnable sTick;

    private NowPlayingBar() {
    }

    private static PlaybackView playingBehind(Activity a) {
        try {
            PlaybackView v = PlaybackPresenter.instance(a).getView();
            if (v != null && v.isEngineBlocked() && PlaybackPresenter.instance(a).getVideo() != null) {
                return v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void onResume(Activity a) {
        if (a instanceof PlaybackActivity) {
            return;
        }
        stopTick();
        sTick = new Runnable() {
            @Override
            public void run() {
                sync(a);
                sHandler.postDelayed(this, 1000);
            }
        };
        sHandler.post(sTick);
    }

    public static void onPause(Activity a) {
        if (a instanceof PlaybackActivity) {
            return;
        }
        stopTick();
    }

    private static void stopTick() {
        if (sTick != null) {
            sHandler.removeCallbacks(sTick);
            sTick = null;
        }
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static void sync(Activity a) {
        if (a.isFinishing()) {
            return;
        }
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View bar = content.findViewWithTag(TAG);
        PlaybackView v = playingBehind(a);
        if (v == null) {
            if (bar != null) {
                bar.setVisibility(View.GONE);
            }
            return;
        }
        if (bar == null) {
            bar = build(a);
            content.addView(bar);
        }
        bar.setVisibility(View.VISIBLE);
        bar.bringToFront();
        Video video = PlaybackPresenter.instance(a).getVideo();
        TextView title = bar.findViewWithTag(TAG + "_title");
        String t = (v.isPlaying() ? "▶  กำลังเล่น: " : "❚❚  หยุดไว้: ") + (video != null && video.getTitle() != null ? video.getTitle() : "");
        if (title != null && !t.contentEquals(title.getText())) {
            title.setText(t);
        }
    }

    private static View build(Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setTag(TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 20), 0, dp(a, 6), 0);
        bar.setElevation(dp(a, 24));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 30));
        bg.setColor(0xEE1F2A44);
        bg.setStroke(dp(a, 1.5f), 0x88FFFFFF);
        bar.setBackground(bg);
        bar.setFocusable(false);

        TextView title = new TextView(a);
        title.setTag(TAG + "_title");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(a, 20));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView back = new TextView(a);
        back.setText("กลับไปดู");
        back.setTextColor(0xFF8AB4FF);
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(a, 19));
        back.setPadding(dp(a, 14), 0, dp(a, 8), 0);
        bar.addView(back);

        TextView stop = new TextView(a);
        stop.setText("✕");
        stop.setTextColor(Color.WHITE);
        stop.setGravity(Gravity.CENTER);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(a, 24));
        stop.setOnClickListener(x -> {
            PlaybackView v = playingBehind(a);
            ViewManager.instance(a).blockTop(null);
            if (v != null) {
                try {
                    v.finishReally();
                } catch (Throwable ignored) {
                }
            }
            bar.setVisibility(View.GONE);
        });
        bar.addView(stop, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)));

        bar.setOnClickListener(x -> {
            bar.setVisibility(View.GONE);
            ViewManager.instance(a).startView(PlaybackView.class);
        });

        int w = Math.min(Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.7f), dp(a, 520));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, dp(a, 56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(a, 16); // bottom: clear of the page's search / account buttons at the top
        bar.setLayoutParams(lp);
        return bar;
    }
}
