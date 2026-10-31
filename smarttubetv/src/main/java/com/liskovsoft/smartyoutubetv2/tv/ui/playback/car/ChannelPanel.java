package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
 * Car build: swipe down from the top of the video -> the channel's latest videos slide down from the top
 * (a row of cards, like the YouTube app). Tap a card to play it; swipe up or tap the video to close.
 */
public final class ChannelPanel {
    public interface Host {
        void onVideoClicked(Video video);
    }

    private final Activity mActivity;
    private final Context mCtx;
    private final Host mHost;
    private final LinearLayout mPanel;
    private final TextView mTitle;
    private final TextView mEmpty;
    private final Adapter mAdapter = new Adapter();
    private float mDensity;
    private boolean mOpen;
    // auto-fit: full size on the car's full screen, a bit smaller in a split screen
    private float mScale = 1f;
    private int mRootW, mRootH;
    private TextView mCloseView, mLatestView;
    private LinearLayout mHeader;
    private RecyclerView mList;

    public ChannelPanel(Activity activity, ViewGroup root, Host host) {
        this(activity, activity, root, host);
    }

    /** @param ctx the window's context (the second screen's, when shown there) */
    public ChannelPanel(Activity activity, Context ctx, ViewGroup root, Host host) {
        mActivity = activity;
        mCtx = ctx;
        mHost = host;
        mPanel = new LinearLayout(ctx);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(0xF2101A3A);
        mPanel.setClickable(true);
        mPanel.setVisibility(View.GONE);
        mPanel.setElevation(dp(20));
        mPanel.setPadding(0, dp(8), 0, dp(12));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        mHeader = header;
        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(ctx);
        mCloseView = close;
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(52)));
        mPanel.addView(header);

        TextView latest = new TextView(ctx);
        mLatestView = latest;
        latest.setText("วิดีโอล่าสุด");
        latest.setTextColor(0xFFCCCCCC);
        mPanel.addView(latest);

        FrameLayout listBox = new FrameLayout(ctx);
        RecyclerView list = new RecyclerView(ctx);
        mList = list;
        list.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        list.setAdapter(mAdapter);
        list.setClipToPadding(false);
        list.setFocusable(false);
        listBox.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mEmpty = new TextView(ctx);
        mEmpty.setTextColor(0xFFAAAAAA);
        mEmpty.setGravity(Gravity.CENTER);
        listBox.addView(mEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        mPanel.addView(listBox);

        // swipe up on the panel closes it
        mPanel.setOnTouchListener(new View.OnTouchListener() {
            private float y0;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    y0 = e.getRawY();
                } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && y0 - e.getRawY() > dp(80)) {
                    close();
                    return true;
                }
                return false;
            }
        });

        root.addView(mPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != mRootW || b - t != mRootH) {
                v.post(() -> fitTo(v.getWidth(), v.getHeight()));
            }
        });
        fitTo(root.getWidth(), root.getHeight());
    }

    /** Text and cards follow the window: full size on the car's screen, smaller in a split screen. */
    private void fitTo(int w, int h) {
        mRootW = w;
        mRootH = h;
        if (w > 0 && h > 0) {
            float d = density();
            mScale = Math.max(0.6f, Math.min(1f, Math.min((h / d) / 720f, (w / d) / 1000f)));
        }
        mHeader.setPadding(sdp(20), 0, sdp(8), sdp(6));
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(30));
        mCloseView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(34));
        ViewGroup.LayoutParams cp = mCloseView.getLayoutParams();
        if (cp != null) {
            cp.width = sdp(64);
            cp.height = sdp(60);
            mCloseView.setLayoutParams(cp);
        }
        mLatestView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(22));
        mLatestView.setPadding(sdp(20), 0, 0, sdp(8));
        mList.setPadding(sdp(12), 0, sdp(12), 0);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(24));
        mAdapter.notifyDataSetChanged();
    }

    private int sdp(float v) {
        return dp(v * mScale);
    }

    /** Card width: full size on wide windows, at most ~45% of a narrow one (2+ cards stay visible). */
    private int cardWidth() {
        int w = sdp(320);
        if (mRootW > 0) {
            w = Math.min(w, Math.round(mRootW * 0.45f));
        }
        return w;
    }

    private int dp(float v) {
        return Math.round(v * density());
    }

    private float density() {
        if (mDensity <= 0) {
            float d = 0;
            try {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                ((android.view.WindowManager) mCtx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
                d = m.density;
            } catch (Exception ignored) {
            }
            mDensity = d > 0 ? d : mCtx.getResources().getDisplayMetrics().density;
        }
        return mDensity;
    }

    public boolean isOpen() {
        return mOpen;
    }

    /** Opens with a title; the videos come with {@link #setItems}. */
    public void open(String channelName) {
        mOpen = true;
        mTitle.setText(channelName != null ? channelName : "ช่องนี้");
        setItems(null, "กำลังโหลด…");
        mPanel.setVisibility(View.VISIBLE);
        mPanel.setTranslationY(-(mPanel.getHeight() > 0 ? mPanel.getHeight() : dp(520)));
        mPanel.animate().translationY(0).setDuration(220).start();
    }

    public void setItems(List<Video> items, String emptyText) {
        mAdapter.set(items);
        boolean empty = items == null || items.isEmpty();
        mEmpty.setText(emptyText);
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    public void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        mPanel.animate().translationY(-mPanel.getHeight()).setDuration(180)
                .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
    }

    public boolean contains(float rawX, float rawY) {
        if (!mOpen) {
            return false;
        }
        int[] loc = new int[2];
        mPanel.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + mPanel.getWidth() && rawY >= loc[1] && rawY <= loc[1] + mPanel.getHeight();
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

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(parent);
        }

        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(mItems.get(position));
        }

        @Override public int getItemCount() {
            return mItems.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final ImageView mThumb;
        private final TextView mName;
        private final TextView mSub;
        private Video mVideo;
        private int mSizedCard = -1;

        Holder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout card = (LinearLayout) itemView;
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new RecyclerView.LayoutParams(dp(272), ViewGroup.LayoutParams.WRAP_CONTENT));
            mThumb = new ImageView(parent.getContext());
            mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mThumb.setBackgroundColor(0xFF2A2A2A);
            card.addView(mThumb, new LinearLayout.LayoutParams(dp(256), dp(144)));
            mName = new TextView(parent.getContext());
            mName.setTextColor(Color.WHITE);
            mName.setMaxLines(2);
            mName.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(mName);
            mSub = new TextView(parent.getContext());
            mSub.setTextColor(0xFFAAAAAA);
            mSub.setMaxLines(1);
            mSub.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(mSub);
            card.setOnClickListener(v -> {
                if (mVideo != null) {
                    close();
                    mHost.onVideoClicked(mVideo);
                }
            });
        }

        private void fit() {
            int card = cardWidth();
            if (card == mSizedCard) {
                return;
            }
            mSizedCard = card;
            int pad = Math.max(dp(4), Math.round(card * 0.025f));
            itemView.setPadding(pad, 0, pad, 0);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) itemView.getLayoutParams();
            lp.width = card;
            itemView.setLayoutParams(lp);
            LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) mThumb.getLayoutParams();
            tp.width = card - pad * 2;
            tp.height = Math.round(tp.width * 9f / 16f);
            mThumb.setLayoutParams(tp);
            float k = card / (float) dp(320);   // text follows the card size
            mName.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(16), dp(28) * k));
            mName.setPadding(0, sdp(8), 0, 0);
            mSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(13), dp(22) * k));
        }

        void bind(Video v) {
            fit();
            mVideo = v;
            mName.setText(v.getTitle());
            CharSequence sub = v.getSecondTitle();
            mSub.setText(sub != null ? sub : v.getAuthor());
            try {
                Glide.with(mActivity).load(v.cardImageUrl).into(mThumb);
            } catch (Exception ignored) {
            }
        }
    }
}
