package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.content.Context;
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
        void setVideoFraction(float fraction, boolean videoOnRight); // 1 = full width
    }

    /** Left menu: a row of section tabs above the list. */
    public interface Tabs {
        String[] titles();
        void onTabSelected(int index, SidePanel panel);
    }

    public static final float VIDEO_FRACTION = 0.62f;

    private final Activity mActivity;
    private final Context mCtx; // views + sizes: the activity, or the second screen's context
    private final Host mHost;
    private final FrameLayout mPanel;
    private final Adapter mAdapter = new Adapter();
    private boolean mOpen;
    private final boolean mLeft;
    private final TextView mEmpty;
    private final List<TextView> mTabViews = new ArrayList<>();

    public SidePanel(Activity activity, ViewGroup root, Host host) {
        this(activity, root, host, false, "วิดีโอถัดไป", null);
    }

    public SidePanel(Activity activity, ViewGroup root, Host host, boolean left, String titleText, Tabs tabs) {
        this(activity, activity, root, host, left, titleText, tabs);
    }

    /** viewContext: where the panel is shown (the second screen has its own size and density). */
    public SidePanel(Activity activity, Context viewContext, ViewGroup root, Host host, boolean left, String titleText, Tabs tabs) {
        mActivity = activity;
        mCtx = viewContext;
        mHost = host;
        mLeft = left;
        boolean mainScreen = viewContext == activity;
        Context vc = viewContext; // all views of the panel are made for the screen it is shown on

        mPanel = new FrameLayout(vc);
        mPanel.setBackgroundColor(0xF2101010);
        mPanel.setVisibility(View.GONE);
        mPanel.setClickable(true);
        mPanel.setElevation(dp(18));

        LinearLayout column = new LinearLayout(vc);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(vc);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), left || !mainScreen ? dp(8) : dp(96), dp(4)); // clear of the on-screen Back button
        TextView title = new TextView(vc);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(20));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(vc);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        column.addView(header);

        if (tabs != null) {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(vc);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(vc);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(dp(10), 0, dp(10), dp(6));
            String[] names = tabs.titles();
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                TextView chip = new TextView(vc);
                chip.setText(names[i]);
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(16), 0, dp(16), 0);
                chip.setOnClickListener(v -> {
                    selectTab(index);
                    tabs.onTabSelected(index, this);
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
                clp.rightMargin = dp(8);
                strip.addView(chip, clp);
                mTabViews.add(chip);
            }
            scroll.addView(strip);
            column.addView(scroll);
            selectTab(0);
        }

        RecyclerView list = new RecyclerView(vc);
        list.setLayoutManager(new LinearLayoutManager(vc));
        list.setAdapter(mAdapter);
        list.setFocusable(false);
        column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mPanel.addView(column);
        mEmpty = new TextView(vc);
        mEmpty.setTextColor(0xFFAAAAAA);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(16));
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setVisibility(View.GONE);
        mPanel.addView(mEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mPanel.setOnTouchListener(new SwipeToClose());
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private final SwipeToClose mSwipe = new SwipeToClose();
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                return mSwipe.onTouch(rv, e);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                left ? Gravity.START : Gravity.END);
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
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }

    public boolean isOpen() {
        return mOpen;
    }

    public void open() {
        List<Video> items = mHost.getSuggestions();
        if (!mLeft && (items == null || items.isEmpty())) {
            android.widget.Toast.makeText(mCtx, "ยังไม่มีรายการวิดีโอถัดไป", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        mAdapter.set(items);
        mOpen = true;
        mHost.setVideoFraction(VIDEO_FRACTION, mLeft);
        mPanel.setVisibility(View.VISIBLE);
        float off = mPanel.getWidth() > 0 ? mPanel.getWidth() : dp(400);
        mPanel.setTranslationX(mLeft ? -off : off);
        mPanel.animate().translationX(0).setDuration(200).start();
    }

    public void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        mHost.setVideoFraction(1f, false);
        mPanel.animate().translationX(mLeft ? -mPanel.getWidth() : mPanel.getWidth()).setDuration(180)
                .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
    }

    /** Refresh the list while open (suggestions keep loading). */
    public void refresh() {
        if (mOpen) {
            mAdapter.set(mHost.getSuggestions());
        }
    }

    /** Show this list (left menu after a section loaded); null = loading. */
    public void setItems(List<Video> items, String emptyText) {
        mAdapter.set(items);
        boolean empty = items == null || items.isEmpty();
        mEmpty.setText(emptyText);
        mEmpty.setVisibility(empty && emptyText != null ? View.VISIBLE : View.GONE);
    }

    private void selectTab(int index) {
        for (int i = 0; i < mTabViews.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(i == index ? 0xFFFFFFFF : 0x33FFFFFF);
            mTabViews.get(i).setBackground(bg);
            mTabViews.get(i).setTextColor(i == index ? Color.BLACK : Color.WHITE);
        }
    }

    /** True when (x, y) in screen pixels falls on the panel. */
    public boolean contains(float rawX, float rawY) {
        if (!mOpen) {
            return false;
        }
        int[] loc = new int[2];
        mPanel.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + mPanel.getWidth() && rawY >= loc[1] && rawY <= loc[1] + mPanel.getHeight();
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
                if ((mLeft ? -dx : dx) > dp(90) && Math.abs(dx) > dy * 2) {
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
