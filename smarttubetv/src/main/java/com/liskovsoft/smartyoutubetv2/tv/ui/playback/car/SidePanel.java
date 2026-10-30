package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.util.ArrayList;
import java.util.List;

/**
 * Car build: swipe from the right edge towards the middle -> the video moves to the left and the
 * right side shows a scrolling list of the next / related videos (like the YouTube app on a tablet).
 * Tap a video to play it; tap the video area or swipe the list back to the right to close.
 */
public final class SidePanel {
    public interface Host {
        List<Video> getSuggestions();
        void onVideoClicked(Video video);
        void setVideoFraction(float fraction); // 1 = full width
    }

    public static final float VIDEO_FRACTION = 0.62f;

    private final Activity mActivity;
    private final Host mHost;
    private final FrameLayout mPanel;
    private final Adapter mAdapter = new Adapter();
    private boolean mOpen;

    public SidePanel(Activity activity, ViewGroup root, Host host) {
        mActivity = activity;
        mHost = host;

        mPanel = new FrameLayout(activity);
        mPanel.setBackgroundColor(0xF2101010);
        mPanel.setVisibility(View.GONE);
        mPanel.setClickable(true);
        mPanel.setElevation(dp(18));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(8), dp(4));
        TextView title = new TextView(activity);
        title.setText("วิดีโอถัดไป");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(20));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        column.addView(header);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(mAdapter);
        list.setFocusable(false);
        column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mPanel.addView(column);
        mPanel.setOnTouchListener(new SwipeToClose());
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private final SwipeToClose mSwipe = new SwipeToClose();
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                return mSwipe.onTouch(rv, e);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(mPanel, lp);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * (1 - VIDEO_FRACTION));
            if (mPanel.getLayoutParams().width != w) {
                mPanel.getLayoutParams().width = w;
                mPanel.requestLayout();
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    public boolean isOpen() {
        return mOpen;
    }

    public void open() {
        List<Video> items = mHost.getSuggestions();
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(mActivity, "ยังไม่มีรายการวิดีโอถัดไป", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        mAdapter.set(items);
        mOpen = true;
        mHost.setVideoFraction(VIDEO_FRACTION);
        mPanel.setVisibility(View.VISIBLE);
        mPanel.setTranslationX(mPanel.getWidth() > 0 ? mPanel.getWidth() : dp(400));
        mPanel.animate().translationX(0).setDuration(200).start();
    }

    public void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        mHost.setVideoFraction(1f);
        mPanel.animate().translationX(mPanel.getWidth()).setDuration(180)
                .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
    }

    /** Refresh the list while open (suggestions keep loading). */
    public void refresh() {
        if (mOpen) {
            mAdapter.set(mHost.getSuggestions());
        }
    }

    /** True when (x, y) in screen pixels falls on the panel. */
    public boolean contains(float rawX, float rawY) {
        if (!mOpen) {
            return false;
        }
        int[] loc = new int[2];
        mPanel.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawY >= loc[1] && rawY <= loc[1] + mPanel.getHeight();
    }

    private final class SwipeToClose implements View.OnTouchListener {
        private float mDownX, mDownY;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                mDownX = e.getRawX();
                mDownY = e.getRawY();
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                float dx = e.getRawX() - mDownX, dy = Math.abs(e.getRawY() - mDownY);
                if (dx > dp(90) && dx > dy * 2) {
                    close();
                    return true;
                }
            }
            return false;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<Video> mItems = new ArrayList<>();

        void set(List<Video> items) {
            mItems.clear();
            if (items != null) {
                mItems.addAll(items);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(mItems.get(position));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mTitle;
        private final TextView mSub;
        private Video mVideo;

        Holder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setBackground(pressable());

            mThumb = new ImageView(parent.getContext());
            mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mThumb.setBackgroundColor(0xFF2A2A2A);
            row.addView(mThumb, new LinearLayout.LayoutParams(dp(176), dp(99)));

            LinearLayout text = new LinearLayout(parent.getContext());
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12), 0, 0, 0);
            mTitle = new TextView(parent.getContext());
            mTitle.setTextColor(Color.WHITE);
            mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(17));
            mTitle.setMaxLines(2);
            mTitle.setEllipsize(TextUtils.TruncateAt.END);
            mSub = new TextView(parent.getContext());
            mSub.setTextColor(0xFFAAAAAA);
            mSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(14));
            mSub.setMaxLines(2);
            mSub.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(mTitle);
            text.addView(mSub);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            row.setOnClickListener(v -> {
                if (mVideo != null) {
                    close();
                    mHost.onVideoClicked(mVideo);
                }
            });
        }

        private android.graphics.drawable.Drawable pressable() {
            android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
            GradientDrawable pressed = new GradientDrawable();
            pressed.setColor(0x33FFFFFF);
            s.addState(new int[] {android.R.attr.state_pressed}, pressed);
            return s;
        }

        void bind(Video v) {
            mVideo = v;
            mTitle.setText(v.getTitle());
            CharSequence sub = v.getSecondTitle();
            mSub.setText(sub != null ? sub : v.getAuthor());
            try {
                Glide.with(mActivity).load(v.cardImageUrl).into(mThumb);
            } catch (Exception ignored) {
            }
        }
    }
}
