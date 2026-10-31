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
        /** Where the video goes while the list is open (pixels inside the root); null = full screen. */
        void setVideoArea(@androidx.annotation.Nullable android.graphics.Rect area);
    }

    /** Search in the menu: typed or spoken words. */
    public interface Search {
        void onSearch(String query, SidePanel panel);
    }

    /** Left menu: a row of section tabs above the list. */
    public interface Tabs {
        String[] titles();
        void onTabSelected(int index, SidePanel panel);
    }

    /** Part of the width the list takes on wide screens (the video keeps the rest). */
    private static final float PANEL_FRACTION = 0.38f;
    /** The list never gets narrower than this (dp) beside the video, so its text stays readable. */
    private static final float MIN_PANEL_DP = 560f;
    /** At this panel width (dp) and above, the list has its full size (the look tuned on the car's screen). */
    private static final float FULL_PANEL_DP = 690f;
    /** Windows narrower than this (width / height) put the list under the video instead of beside it. */
    private static final float STACK_BELOW_ASPECT = 1.25f;

    // ---- auto-fit: the list and the video share whatever window the app has (full screen, split screen, screen 2)
    private final ViewGroup mRoot;
    private int mRootW, mRootH;
    private boolean mStacked;          // list under the video (narrow window)
    private int mPanelW, mPanelH;
    private float mScale = 1f;         // 1 = full size, smaller in tight windows
    private final android.graphics.Rect mVideoArea = new android.graphics.Rect();
    private TextView mTitleView, mCloseView, mGoView;
    private android.widget.HorizontalScrollView mTabScroll;
    private LinearLayout mTabStrip;

    private final Activity mActivity;
    private final Context mCtx; // views + sizes: the activity, or the second screen's context
    private final Host mHost;
    private final FrameLayout mPanel;
    private final Adapter mAdapter = new Adapter();
    private boolean mOpen;
    private final boolean mLeft;
    private final TextView mEmpty;
    private final List<TextView> mTabViews = new ArrayList<>();
    private LinearLayout mHeader, mColumn;
    private LinearLayout mSearchRow;
    private android.widget.EditText mSearchBox;
    private TextView mMic;
    private Search mSearch;
    private boolean mShowingSearch;
    private android.speech.SpeechRecognizer mRecognizer;
    private TextView mSearchOpen;

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
        mTitleView = title;
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(32));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mHeader = header;
        mColumn = column;
        TextView close = new TextView(vc);
        mCloseView = close;
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(34));
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(64), dp(64)));
        column.addView(header);

        if (tabs != null) {
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(vc);
            scroll.setHorizontalScrollBarEnabled(false);
            mTabScroll = scroll;
            LinearLayout strip = new LinearLayout(vc);
            mTabStrip = strip;
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(dp(10), 0, dp(10), dp(6));
            String[] names = tabs.titles();
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                TextView chip = new TextView(vc);
                chip.setText(names[i]);
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(20), 0, dp(20), 0);
                chip.setOnClickListener(v -> {
                    selectTab(index);
                    tabs.onTabSelected(index, this);
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(58));
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
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
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

        mRoot = root;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                left ? Gravity.START : Gravity.END);
        root.addView(mPanel, lp);
        // the window can be any size: full screen, either side of a split screen, the second screen
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != mRootW || b - t != mRootH) {
                v.post(() -> fitTo(v.getWidth(), v.getHeight()));
            }
        });
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            fitTo(root.getWidth(), root.getHeight());
        } else {
            applySizes();
        }
    }

    /**
     * Works out how the list and the video share a window of this size, and how big the list's
     * text and pictures should be. On the car's full screen the list keeps its full size; in a
     * split screen it gets a fair share of the width (or goes under the video when the window
     * is narrow) and shrinks a little so titles still fit.
     */
    private void fitTo(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean changed = w != mRootW || h != mRootH;
        mRootW = w;
        mRootH = h;
        float d = density();
        float wDp = w / d, hDp = h / d;
        mStacked = w < h * STACK_BELOW_ASPECT;
        if (!mStacked) {
            float panelDp = Math.max(wDp * PANEL_FRACTION, Math.min(MIN_PANEL_DP, wDp * 0.55f));
            mPanelW = Math.round(panelDp * d);
            mPanelH = h;
            mScale = clamp(Math.min(panelDp / FULL_PANEL_DP, hDp / 720f), 0.6f, 1f);
            int videoLeft = mLeft ? mPanelW : 0;
            mVideoArea.set(videoLeft, 0, videoLeft + (w - mPanelW), h);
        } else {
            int videoH = Math.min(Math.round(w * 9f / 16f), Math.round(h * 0.5f));
            mPanelW = w;
            mPanelH = h - videoH;
            mScale = clamp(Math.min(wDp / FULL_PANEL_DP, (mPanelH / d) / 560f), 0.6f, 1f);
            mVideoArea.set(0, 0, w, videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        lp.width = mStacked ? ViewGroup.LayoutParams.MATCH_PARENT : mPanelW;
        lp.height = mStacked ? mPanelH : ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = mStacked ? Gravity.BOTTOM : (mLeft ? Gravity.START : Gravity.END);
        mPanel.setLayoutParams(lp);
        applySizes();
        if (mOpen && changed) {
            mHost.setVideoArea(new android.graphics.Rect(mVideoArea));
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** dp scaled to the window (text, pictures, buttons of the list). */
    private int sdp(float v) {
        return dp(v * mScale);
    }

    private void applySizes() {
        boolean mainScreen = mCtx == mActivity;
        if (mHeader != null) {
            mHeader.setPadding(sdp(16), sdp(8), mLeft || !mainScreen || mStacked ? sdp(8) : dp(96), sdp(4)); // clear of the on-screen Back button
        }
        if (mTitleView != null) {
            mTitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(32));
        }
        if (mCloseView != null) {
            mCloseView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(34));
            setSize(mCloseView, sdp(64), sdp(64));
        }
        if (mSearchOpen != null) {
            mSearchOpen.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(34));
            setSize(mSearchOpen, sdp(68), sdp(64));
        }
        if (mTabStrip != null) {
            mTabStrip.setPadding(sdp(10), 0, sdp(10), sdp(6));
        }
        for (TextView chip : mTabViews) {
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
            chip.setPadding(sdp(20), 0, sdp(20), 0);
            ViewGroup.LayoutParams p = chip.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams) {
                p.height = sdp(58);
                ((LinearLayout.LayoutParams) p).rightMargin = sdp(8);
                chip.setLayoutParams(p);
            }
        }
        if (mEmpty != null) {
            mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
        }
        if (mSearchBox != null && mSearchRow != null) {   // the inline search row (second screen)
            sizeSearchRow();
        }
        mAdapter.notifyDataSetChanged();   // rows pick up the new size when they are bound again
    }

    private static void setSize(View v, int w, int h) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p != null && (p.width != w || p.height != h)) {
            p.width = w;
            p.height = h;
            v.setLayoutParams(p);
        }
    }

    /** Thumbnail width of a row: full size on wide windows, never more than ~40% of the list. */
    private int thumbWidth() {
        int w = sdp(250);
        if (mPanelW > 0) {
            w = Math.min(w, Math.round(mPanelW * 0.42f));
        }
        return w;
    }

    // ---- search (left menu): a 🔍 button in the header opens a search bar (keyboard or 🎤 voice)

    public void setSearch(Search search) {
        mSearch = search;
        if (mHeader == null || mSearchOpen != null) {
            return;
        }
        Context vc = mHeader.getContext();
        TextView open = new TextView(vc);
        open.setText("🔍");
        open.setTextColor(Color.WHITE);
        open.setGravity(Gravity.CENTER);
        open.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(34));
        mSearchOpen = open;
        mHeader.addView(open, mHeader.getChildCount() - 1, new LinearLayout.LayoutParams(sdp(68), sdp(64)));
        if (mCtx == mActivity) {
            // main screen: the search box lives in its own small window, so the player can't take its keys or focus
            open.setOnClickListener(v -> openSearchDialog());
            return;
        }
        open.setOnClickListener(v -> showSearch(mSearchRow.getVisibility() != View.VISIBLE));
        mSearchRow = buildSearchRow(vc);
        mSearchRow.setVisibility(View.GONE);
        mColumn.addView(mSearchRow, 1);   // right under the header
    }

    private LinearLayout buildSearchRow(Context vc) {
        LinearLayout row = new LinearLayout(vc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(sdp(12), 0, sdp(12), sdp(8));

        mSearchBox = new android.widget.EditText(vc);
        mSearchBox.setHint("ค้นหาวิดีโอ…");
        mSearchBox.setHintTextColor(0xFF9A9A9A);
        mSearchBox.setTextColor(Color.WHITE);
        mSearchBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(28));
        mSearchBox.setSingleLine(true);
        mSearchBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        mSearchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        mSearchBox.setPadding(sdp(16), 0, sdp(16), 0);
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xFF2A2A2A);
        box.setCornerRadius(sdp(24));
        mSearchBox.setBackground(box);
        mSearchBox.setOnEditorActionListener((v, actionId, event) -> {
            submitSearch();
            return true;
        });
        row.addView(mSearchBox, new LinearLayout.LayoutParams(0, sdp(60), 1));

        mMic = chipButton(vc, "🎤", v -> listen());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(sdp(68), sdp(60));
        mlp.leftMargin = sdp(8);
        row.addView(mMic, mlp);
        TextView go = chipButton(vc, "ค้นหา", v -> submitSearch());
        mGoView = go;
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, sdp(60));
        glp.leftMargin = sdp(8);
        row.addView(go, glp);
        return row;
    }

    /** The inline search row follows the window size too. */
    private void sizeSearchRow() {
        mSearchRow.setPadding(sdp(12), 0, sdp(12), sdp(8));
        mSearchBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(28));
        mSearchBox.setPadding(sdp(16), 0, sdp(16), 0);
        ViewGroup.LayoutParams bp = mSearchBox.getLayoutParams();
        if (bp != null) {
            bp.height = sdp(60);
            mSearchBox.setLayoutParams(bp);
        }
        if (mMic != null) {
            mMic.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
            setSize(mMic, sdp(68), sdp(60));
        }
        if (mGoView != null) {
            mGoView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
            mGoView.setPadding(sdp(14), 0, sdp(14), 0);
            ViewGroup.LayoutParams gp = mGoView.getLayoutParams();
            if (gp != null) {
                gp.height = sdp(60);
                mGoView.setLayoutParams(gp);
            }
        }
    }

    private android.app.Dialog mSearchDialog;

    /** Main screen: a small search window at the top of the panel (keyboard, 🎤 voice, ค้นหา). */
    private void openSearchDialog() {
        if (mSearchDialog != null && mSearchDialog.isShowing()) {
            return;
        }
        android.app.Dialog d = new android.app.Dialog(mActivity);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout row = buildSearchRow(mActivity);
        row.setPadding(sdp(12), sdp(10), sdp(12), sdp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2161616);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        row.setBackground(bg);
        d.setContentView(row);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // over the list: beside the video on wide windows, under it on narrow ones
            w.setGravity(Gravity.TOP | (mStacked ? Gravity.CENTER_HORIZONTAL : (mLeft ? Gravity.START : Gravity.END)));
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = Math.max(Math.min(sdp(360), mPanel.getWidth()), mPanel.getWidth() - sdp(16));
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.x = mStacked ? 0 : sdp(8);
            lp.y = (mStacked ? mVideoArea.bottom : 0) + sdp(62);
            w.setAttributes(lp);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.setOnDismissListener(x -> {
            if (mRecognizer != null) {
                mRecognizer.destroy();
                mRecognizer = null;
            }
            mSearchDialog = null;
        });
        mSearchDialog = d;
        d.show();
        mSearchBox.requestFocus();
    }

    private TextView chipButton(Context vc, String text, View.OnClickListener click) {
        TextView b = new TextView(vc);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
        b.setGravity(Gravity.CENTER);
        b.setPadding(sdp(14), 0, sdp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(sdp(24));
        b.setBackground(bg);
        b.setOnClickListener(click);
        return b;
    }

    /** Opens the search box (the player's 🔍 button). */
    public void startSearch() {
        if (mSearch == null) {
            return;
        }
        if (mCtx == mActivity) {
            openSearchDialog();
        } else {
            showSearch(true);
        }
    }

    private void showSearch(boolean show) {
        if (mSearchRow == null) {
            return;
        }
        mSearchRow.setVisibility(show ? View.VISIBLE : View.GONE);
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                mSearchBox.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (show) {
            mSearchBox.requestFocus();
            if (imm != null) {
                mSearchBox.post(() -> imm.showSoftInput(mSearchBox, 0));
            }
        } else if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchBox.getWindowToken(), 0);
        }
    }

    private void submitSearch() {
        String q = mSearchBox.getText().toString().trim();
        if (q.isEmpty() || mSearch == null) {
            return;
        }
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                mSearchBox.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchBox.getWindowToken(), 0);
        }
        mSearchBox.clearFocus();
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
        }
        mShowingSearch = true;
        selectTab(-1);
        setItems(null, "กำลังค้นหา “" + q + "”…");
        mSearch.onSearch(q, this);
    }

    /** Voice search (Thai), right here without leaving the video. */
    private void listen() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(mActivity)) {
            android.widget.Toast.makeText(mCtx, "เครื่องนี้ไม่มีระบบฟังเสียง พิมพ์แทนได้", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && mActivity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mActivity.requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 7301);
            android.widget.Toast.makeText(mCtx, "อนุญาตไมโครโฟน แล้วกด 🎤 อีกครั้ง", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (mRecognizer != null) {
            mRecognizer.destroy();
        }
        mRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(mActivity);
        mRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle b) { mMic.setText("●"); mSearchBox.setHint("พูดได้เลย…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { mMic.setText("🎤"); }
            @Override public void onError(int e) {
                mMic.setText("🎤");
                mSearchBox.setHint("ค้นหาวิดีโอ…");
                android.widget.Toast.makeText(mCtx, "ไม่ได้ยิน ลองพูดอีกครั้ง", android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override public void onResults(android.os.Bundle b) {
                mMic.setText("🎤");
                mSearchBox.setHint("ค้นหาวิดีโอ…");
                java.util.ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) {
                    mSearchBox.setText(r.get(0));
                    submitSearch();
                }
            }
            @Override public void onPartialResults(android.os.Bundle b) {
                java.util.ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) {
                    mSearchBox.setText(r.get(0));
                }
            }
            @Override public void onEvent(int t, android.os.Bundle b) { }
        });
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "th-TH")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            mRecognizer.startListening(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(mCtx, "เปิดไมค์ไม่ได้", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Sizes follow the screen itself, not the player's shrunken layout: the lists must stay readable
     * at arm's length in the car (the player's controls are drawn smaller on purpose).
     */
    private float mDensity;

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

    public void open() {
        List<Video> items = mHost.getSuggestions();
        if (!mLeft && (items == null || items.isEmpty()) && mSearch == null) {
            android.widget.Toast.makeText(mCtx, "ยังไม่มีรายการวิดีโอถัดไป", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mLeft) {   // next videos: back to the suggestions each time it opens
            mShowingSearch = false;
            setItems(items, "ยังไม่มีวิดีโอถัดไป  แตะ 🔍 เพื่อค้นหา");
        }
        mOpen = true;
        if (mRoot.getWidth() > 0 && (mRoot.getWidth() != mRootW || mRoot.getHeight() != mRootH)) {
            fitTo(mRoot.getWidth(), mRoot.getHeight());
        }
        mHost.setVideoArea(new android.graphics.Rect(mVideoArea));
        mPanel.setVisibility(View.VISIBLE);
        if (mStacked) {   // slides up under the video
            mPanel.setTranslationX(0);
            mPanel.setTranslationY(mPanelH > 0 ? mPanelH : dp(300));
            mPanel.animate().translationY(0).setDuration(200).start();
        } else {
            float off = mPanel.getWidth() > 0 ? mPanel.getWidth() : mPanelW > 0 ? mPanelW : dp(400);
            mPanel.setTranslationY(0);
            mPanel.setTranslationX(mLeft ? -off : off);
            mPanel.animate().translationX(0).setDuration(200).start();
        }
    }

    public void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        if (mSearchRow != null && mSearchRow.getVisibility() == View.VISIBLE) {
            showSearch(false);
        }
        if (mSearchDialog != null) {
            mSearchDialog.dismiss();
        }
        if (mRecognizer != null) {
            mRecognizer.destroy();
            mRecognizer = null;
        }
        mHost.setVideoArea(null);
        if (mStacked) {
            mPanel.animate().translationY(mPanel.getHeight()).setDuration(180)
                    .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
        } else {
            mPanel.animate().translationX(mLeft ? -mPanel.getWidth() : mPanel.getWidth()).setDuration(180)
                    .withEndAction(() -> mPanel.setVisibility(View.GONE)).start();
        }
    }

    /** Refresh the list while open (suggestions keep loading). */
    public void refresh() {
        if (mOpen && !mShowingSearch && !mLeft) {
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
            bg.setCornerRadius(sdp(22));
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
        private final LinearLayout mText;
        private Video mVideo;
        private float mSizedScale = -1f;
        private int mSizedThumb = -1;

        Holder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout row = (LinearLayout) itemView;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setBackground(pressable());

            mThumb = new ImageView(parent.getContext());
            mThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            mThumb.setBackgroundColor(0xFF2A2A2A);
            row.addView(mThumb, new LinearLayout.LayoutParams(dp(250), dp(140)));

            LinearLayout text = new LinearLayout(parent.getContext());
            mText = text;
            text.setOrientation(LinearLayout.VERTICAL);
            mTitle = new TextView(parent.getContext());
            mTitle.setTextColor(Color.WHITE);
            mTitle.setMaxLines(3);
            mTitle.setLineSpacing(0, 0.92f);
            mTitle.setEllipsize(TextUtils.TruncateAt.END);
            mSub = new TextView(parent.getContext());
            mSub.setTextColor(0xFFAAAAAA);
            mSub.setMaxLines(1);
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

        /** Sizes for the current window (full screen, split screen, ...). */
        private void fit() {
            int thumbW = thumbWidth();
            if (mSizedScale == mScale && mSizedThumb == thumbW) {
                return;
            }
            mSizedScale = mScale;
            mSizedThumb = thumbW;
            itemView.setPadding(sdp(12), sdp(10), sdp(12), sdp(10));
            LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) mThumb.getLayoutParams();
            tp.width = thumbW;
            tp.height = Math.round(thumbW * 9f / 16f);
            mThumb.setLayoutParams(tp);
            mText.setPadding(sdp(12), 0, 0, 0);
            mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(34));
            mSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, sdp(26));
        }

        void bind(Video v) {
            fit();
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
