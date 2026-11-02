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
 * Car build: touch controls on the second screen, laid out like the main screen's player: big round
 * buttons near the top, title and channel line, the main screen's two rows of player buttons around a
 * red seek bar, remaining / elapsed time. Tap the video to show them
 * (they hide after 5 s), previous / back 10 s / play-pause / forward 10 s / next, a seek bar, speed,
 * double-tap the left or right side to skip 10 s, swipe up or in from the right edge for the next videos
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

    /** The main screen's player buttons (quality, like, subtitles, ...). */
    interface Buttons {
        java.util.List<java.util.List<androidx.leanback.widget.Action>> rows();
        void click(androidx.leanback.widget.Action action, boolean longClick);
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
    private final TextView mTitle;
    private final TextView mMeta;
    private final TextView mRemaining;
    private final LinearLayout mTop;
    private final LinearLayout mCenter;
    private final LinearLayout mRow1;
    private final LinearLayout mRow2;
    private String mRowsKey = "";
    private final Buttons mButtons;
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

    ScreenTwoControls(Context ctx, Activity activity, FrameLayout root, PlayerSource player, Panels panels,
                      Buttons buttonsSource) {
        mCtx = ctx;
        mActivity = activity;
        mPlayer = player;
        mPanels = panels;
        mButtons = buttonsSource;

        // big round buttons near the top, same as the main screen (CenterControls)
        mCenter = new LinearLayout(ctx);
        mCenter.setOrientation(LinearLayout.HORIZONTAL);
        mCenter.setGravity(Gravity.CENTER);
        mCenter.setVisibility(View.GONE);
        mCenter.addView(button("|◀◀", 76, v -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        mCenter.addView(button("↺ 10", 76, v -> seekBy(-SEEK_MS)));
        mPlayPause = button("❚❚", 96, v -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        mCenter.addView(mPlayPause);
        mCenter.addView(button("10 ↻", 76, v -> seekBy(SEEK_MS)));
        mCenter.addView(button("▶▶|", 76, v -> key(KeyEvent.KEYCODE_MEDIA_NEXT)));
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cLp.topMargin = dp(70);
        mCenter.setElevation(dp(16));

        // bottom: title, channel line, buttons row 1, seek bar, buttons row 2 + time (main screen layout)
        mPanel = new LinearLayout(ctx);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(dp(32), dp(40), dp(32), dp(14));
        mPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x99000000, 0x00000000}));
        mPanel.setVisibility(View.GONE);

        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTitle.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        mPanel.addView(mTitle);
        mMeta = new TextView(ctx);
        mMeta.setTextColor(0xFFDDDDDD);
        mMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        mMeta.setSingleLine(true);
        mMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mMeta.setPadding(0, dp(4), 0, dp(6));
        mPanel.addView(mMeta);

        mRow1 = iconRow();
        mPanel.addView(mRow1);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mSeek = new SeekBar(ctx);
        mSeek.setMax(1000);
        mSeek.setPadding(dp(8), 0, dp(8), 0);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            mSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF0000));
            mSeek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x88FFFFFF));
            mSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        }
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
        bar.addView(mSeek, new LinearLayout.LayoutParams(0, dp(40), 1));
        mPanel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        mRow2 = iconRow();
        bottom.addView(mRow2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout times = new LinearLayout(ctx);
        times.setOrientation(LinearLayout.VERTICAL);
        times.setGravity(Gravity.END);
        mRemaining = new TextView(ctx);
        mRemaining.setTextColor(Color.WHITE);
        mRemaining.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        times.addView(mRemaining);
        mTime = new TextView(ctx);
        mTime.setTextColor(Color.WHITE);
        mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        times.addView(mTime);
        bottom.addView(times);
        mPanel.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(mCenter, cLp);

        // top right: the lists of this screen (menu, next videos)
        mTop = new LinearLayout(ctx);
        mTop.setOrientation(LinearLayout.HORIZONTAL);
        mTop.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mTop.setPadding(dp(16), dp(10), dp(16), dp(10));
        mTop.setVisibility(View.GONE);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        menuLp.rightMargin = dp(10);
        mTop.addView(chip("☰  เมนู", v -> {
            setShown(false);
            mPanels.openMenu();
        }), menuLp);
        mTop.addView(chip("วิดีโอถัดไป  ▤", v -> {
            setShown(false);
            mPanels.openNext();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        mTop.setElevation(dp(20));
        root.addView(mTop, topLp);

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
                    // swipe up on the video: the next videos to pick from (like swiping up on the main screen)
                    if (!mSwiped && !mPanels.anyOpen() && mDownY > h * 0.3f && mDownY - e.getY() > h * 0.18f
                            && mDownY - e.getY() > Math.abs(dx) * 2) {
                        mSwiped = true;
                        setShown(false);
                        mPanels.openNext();
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

    private LinearLayout iconRow() {
        LinearLayout row = new LinearLayout(mCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return row;
    }

    /** Rebuilds the two button rows when the main screen's buttons change (new video, toggled like, ...). */
    private void refreshRows() {
        java.util.List<java.util.List<androidx.leanback.widget.Action>> rows;
        try {
            rows = mButtons.rows();
        } catch (Throwable t) {
            return;
        }
        StringBuilder key = new StringBuilder();
        for (java.util.List<androidx.leanback.widget.Action> r : rows) {
            for (androidx.leanback.widget.Action a : r) {
                key.append(a.getId()).append(':').append(System.identityHashCode(a.getIcon())).append(',');
            }
            key.append('|');
        }
        if (key.toString().equals(mRowsKey)) {
            return;
        }
        mRowsKey = key.toString();
        fillRow(mRow1, rows.size() > 0 ? rows.get(0) : null);
        fillRow(mRow2, rows.size() > 1 ? rows.get(1) : null);
    }

    private void fillRow(LinearLayout row, java.util.List<androidx.leanback.widget.Action> actions) {
        row.removeAllViews();
        if (actions == null) {
            return;
        }
        for (androidx.leanback.widget.Action a : actions) {
            if (a.getId() == com.liskovsoft.smartyoutubetv2.tv.R.id.action_pip) {
                continue; // picture-in-picture makes no sense on the second screen
            }
            android.widget.ImageView icon = new android.widget.ImageView(mCtx);
            android.graphics.drawable.Drawable d = a.getIcon();
            if (d != null && d.getConstantState() != null) {
                d = d.getConstantState().newDrawable(mCtx.getResources()).mutate();
            }
            icon.setImageDrawable(d);
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(12), dp(12), dp(12), dp(12));
            CharSequence label = a.getLabel1() != null ? a.getLabel1() : a.getLabel2();
            if (label != null) {
                icon.setContentDescription(label);
            }
            android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
            GradientDrawable pressed = new GradientDrawable();
            pressed.setShape(GradientDrawable.OVAL);
            pressed.setColor(0x55FFFFFF);
            bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
            icon.setBackground(bg);
            icon.setOnClickListener(v -> {
                mButtons.click(a, false);
                showFor();
                mHandler.postDelayed(this::refreshRows, 400);
            });
            icon.setOnLongClickListener(v -> {
                mButtons.click(a, true);
                showFor();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60));
            lp.rightMargin = dp(14);
            row.addView(icon, lp);
        }
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
        lp.leftMargin = lp.rightMargin = dp(22);
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
        mCenter.setVisibility(shown ? View.VISIBLE : View.GONE);
        mHandler.removeCallbacks(mTick);
        if (shown) {
            refreshRows();
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
        try {
            com.liskovsoft.smartyoutubetv2.common.app.models.data.Video v =
                    com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter.instance(mActivity).getVideo();
            String title = v != null ? v.getTitleFull() : null;
            if (title != null && !title.contentEquals(mTitle.getText())) {
                mTitle.setText(title);
            }
            CharSequence meta = v != null ? v.getSecondTitleFull() : null;
            if (meta == null && v != null) {
                meta = v.getAuthor();
            }
            if (meta != null && !meta.toString().contentEquals(mMeta.getText())) {
                mMeta.setText(meta);
            }
        } catch (Throwable ignored) {
        }
        refreshRows();
        long dur = p.getDuration(), pos = p.getCurrentPosition();
        if (dur > 0 && !mDragging) {
            mSeek.setProgress((int) (pos * 1000 / dur));
        }
        mRemaining.setText(dur > 0 ? "Remaining: " + fmt(dur - pos) : "");
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
