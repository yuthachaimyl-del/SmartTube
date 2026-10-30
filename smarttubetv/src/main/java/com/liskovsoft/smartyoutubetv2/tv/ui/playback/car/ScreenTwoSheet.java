package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.leanback.widget.Action;
import androidx.leanback.widget.PlaybackControlsRow;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.car.CarState;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Car build: the second screen's "options" sheet. Shows every player button of the main screen
 * (quality, subtitles, like, subscribe, repeat, ...) and the dialogs they open, right on the second screen.
 */
final class ScreenTwoSheet implements CarState.DialogSink {
    interface Host {
        List<Action> actions();
        void click(Action action);
        void longClick(Action action);
        void openChannel();
        void openSearch();
    }

    private static final int PAGE_ACTIONS = 0;
    private static final int PAGE_DIALOG = 1;
    private static final int PAGE_CATEGORY = 2;

    private static final class Page {
        final int kind;
        final CharSequence title;
        final List<OptionCategory> categories;
        final OptionCategory category;

        Page(int kind, CharSequence title, List<OptionCategory> categories, OptionCategory category) {
            this.kind = kind;
            this.title = title;
            this.categories = categories;
            this.category = category;
        }
    }

    private final Context mCtx;
    private final Host mHost;
    private final FrameLayout mScrim;
    private final TextView mTitle;
    private final TextView mBack;
    private final LinearLayout mBody;
    private final ScrollView mScroll;
    private final List<Page> mPages = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mOnClosed;
    private int mVersion; // bumps on every page change (nested dialogs while a click runs)

    ScreenTwoSheet(Context ctx, FrameLayout root, Host host) {
        mCtx = ctx;
        mHost = host;

        mScrim = new FrameLayout(ctx);
        mScrim.setBackgroundColor(0x99000000);
        mScrim.setVisibility(View.GONE);
        mScrim.setElevation(dp(30));
        mScrim.setOnClickListener(v -> closeAll());

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // taps on the card don't close it
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2181C2A);
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setPadding(0, dp(6), 0, dp(10));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(6), 0);
        mBack = headerButton("‹", v -> goBackInternal(true));
        header.addView(mBack, new LinearLayout.LayoutParams(dp(60), dp(60)));
        mTitle = new TextView(ctx);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setPadding(dp(8), 0, dp(8), 0);
        header.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(headerButton("✕", v -> closeAll()), new LinearLayout.LayoutParams(dp(60), dp(60)));
        card.addView(header);

        mScroll = new ScrollView(ctx);
        mBody = new LinearLayout(ctx);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.setPadding(dp(14), 0, dp(14), 0);
        mScroll.addView(mBody, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        mScrim.addView(card, cardLp);
        root.addView(mScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            int cw = Math.min(w - dp(32), Math.max(dp(560), Math.round(w * 0.62f)));
            int ch = h - dp(40);
            if (cardLp.width != cw || cardLp.height != ch) {
                cardLp.width = cw;
                cardLp.height = ch;
                card.post(() -> card.setLayoutParams(cardLp));
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }

    private TextView headerButton(String text, View.OnClickListener click) {
        TextView b = new TextView(mCtx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        b.setOnClickListener(click);
        return b;
    }

    boolean isShown() {
        return mScrim.getVisibility() == View.VISIBLE;
    }

    /** The player buttons page (same buttons as the main screen). */
    void openActions() {
        mPages.clear();
        mPages.add(new Page(PAGE_ACTIONS, "ตัวเลือกวิดีโอ", null, null));
        mVersion++;
        mScrim.setVisibility(View.VISIBLE);
        render();
    }

    void release() {
        mHandler.removeCallbacksAndMessages(null);
        if (isOpen()) {
            dialogClosed();
        }
    }

    // ---- CarState.DialogSink ----

    @Override
    public boolean isOpen() {
        if (!isShown()) {
            return false;
        }
        for (Page p : mPages) {
            if (p.kind != PAGE_ACTIONS) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void show(CharSequence title, List<OptionCategory> categories, Runnable onClosed) {
        mOnClosed = onClosed;
        mPages.add(new Page(PAGE_DIALOG, title, categories, null));
        mVersion++;
        mScrim.setVisibility(View.VISIBLE);
        // one category of a list: show its items directly
        if (categories != null && categories.size() == 1 && isList(categories.get(0))) {
            OptionCategory c = categories.get(0);
            mPages.set(mPages.size() - 1, new Page(PAGE_DIALOG, title != null ? title : c.title, categories, c));
        }
        render();
    }

    @Override
    public void goBack() {
        goBackInternal(true);
    }

    @Override
    public void close() {
        // the app closes its dialog (e.g. after a quality was picked): back to the video
        hideAll();
    }

    // ---- pages ----

    private void closeAll() {
        hideAll();
    }

    private void hideAll() {
        boolean wasDialog = isOpen();
        mPages.clear();
        mVersion++;
        mScrim.setVisibility(View.GONE);
        mBody.removeAllViews();
        if (wasDialog) {
            dialogClosed();
        }
    }

    private void dialogClosed() {
        Runnable r = mOnClosed;
        mOnClosed = null;
        if (r != null) {
            r.run();
        }
    }

    private void goBackInternal(boolean user) {
        if (mPages.isEmpty()) {
            hideAll();
            return;
        }
        boolean wasDialog = isOpen();
        mPages.remove(mPages.size() - 1);
        mVersion++;
        if (mPages.isEmpty()) {
            mScrim.setVisibility(View.GONE);
            mBody.removeAllViews();
            if (wasDialog) {
                dialogClosed();
            }
            return;
        }
        if (wasDialog && !isOpen()) {
            dialogClosed();
        }
        render();
    }

    private static boolean isList(OptionCategory c) {
        return c.type == OptionCategory.TYPE_RADIO_LIST || c.type == OptionCategory.TYPE_CHECKBOX_LIST
                || c.type == OptionCategory.TYPE_STRING_LIST || c.type == OptionCategory.TYPE_LONG_TEXT;
    }

    private void render() {
        mBody.removeAllViews();
        if (mPages.isEmpty()) {
            return;
        }
        Page p = mPages.get(mPages.size() - 1);
        mTitle.setText(p.title != null ? p.title : "");
        mBack.setVisibility(mPages.size() > 1 ? View.VISIBLE : View.INVISIBLE);
        if (p.kind == PAGE_ACTIONS) {
            renderActions();
        } else if (p.category != null) {
            renderItems(p.category, p.kind == PAGE_CATEGORY);
        } else if (p.categories != null) {
            for (OptionCategory c : p.categories) {
                renderCategory(c);
            }
        }
        mScroll.scrollTo(0, 0);
    }

    // the main screen's player buttons as big tiles
    private void renderActions() {
        List<Action> actions = new ArrayList<>();
        for (Action a : mHost.actions()) {
            if (a.getId() != R.id.action_pip) { // picture-in-picture makes no sense on the second screen
                actions.add(a);
            }
        }
        if (actions.isEmpty()) {
            mBody.addView(text("ยังไม่มีปุ่ม (รอวิดีโอเริ่มเล่นก่อน)", 18, 0xFFAAAAAA));
            return;
        }
        int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < actions.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(mCtx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                mBody.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(tile(actions.get(i)), new LinearLayout.LayoutParams(0, dp(124), 1));
        }
        for (int i = actions.size() % perRow; i != 0 && i < perRow; i++) {
            row.addView(new View(mCtx), new LinearLayout.LayoutParams(0, dp(124), 1));
        }
    }

    private View tile(Action a) {
        LinearLayout t = new LinearLayout(mCtx);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        boolean on = a instanceof PlaybackControlsRow.MultiAction && ((PlaybackControlsRow.MultiAction) a).getIndex() == 1
                && a instanceof com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.TwoStateAction;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? 0x553D7BFF : 0x22FFFFFF);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(14));
        pressed.setColor(0x55FFFFFF);
        s.addState(new int[] {android.R.attr.state_pressed}, pressed);
        s.addState(new int[] {}, bg);
        t.setBackground(s);
        ImageView icon = new ImageView(mCtx);
        Drawable d = a.getIcon();
        if (d != null && d.getConstantState() != null) {
            icon.setImageDrawable(d.getConstantState().newDrawable(mCtx.getResources()).mutate());
        } else {
            icon.setImageDrawable(d);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        CharSequence label = a.getLabel1();
        if (label == null || label.length() == 0) {
            label = a.getLabel2();
        }
        TextView l = text(label != null ? label : "", 15, Color.WHITE);
        l.setGravity(Gravity.CENTER);
        l.setMaxLines(2);
        l.setPadding(0, dp(6), 0, 0);
        t.addView(l);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        FrameLayout box = new FrameLayout(mCtx);
        box.setPadding(dp(5), dp(5), dp(5), dp(5));
        box.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.setOnClickListener(v -> onActionClicked(a, false));
        t.setOnLongClickListener(v -> {
            onActionClicked(a, true);
            return true;
        });
        return box;
    }

    private void onActionClicked(Action a, boolean longClick) {
        long id = a.getId();
        if (id == R.id.action_channel && !longClick) {
            hideAll();
            mHost.openChannel();
            return;
        }
        if (id == R.id.action_search && !longClick) {
            hideAll();
            mHost.openSearch();
            return;
        }
        int version = mVersion;
        CarState.armDialog(4_000); // a dialog it opens comes here, not to the main screen
        if (longClick) {
            mHost.longClick(a);
        } else {
            mHost.click(a);
        }
        // toggles (like, subscribe, repeat, ...) change their icon: refresh if still on this page
        mHandler.postDelayed(() -> {
            if (mVersion == version && isShown() && !mPages.isEmpty()
                    && mPages.get(mPages.size() - 1).kind == PAGE_ACTIONS) {
                render();
            }
        }, 400);
    }

    // a category inside a dialog with several categories
    private void renderCategory(OptionCategory c) {
        if (c.options == null || c.options.isEmpty()) {
            return;
        }
        if (c.type == OptionCategory.TYPE_SINGLE_SWITCH) {
            OptionItem item = c.options.get(0);
            mBody.addView(row((item.isSelected() ? "☑  " : "☐  ") + item.getTitle(), item.getDescription(), false,
                    () -> item.onSelect(!item.isSelected())));
        } else if (c.type == OptionCategory.TYPE_SINGLE_BUTTON) {
            OptionItem item = c.options.get(0);
            mBody.addView(row(item.getTitle(), item.getDescription(), false, () -> item.onSelect(true)));
        } else if (c.type == OptionCategory.TYPE_LONG_TEXT) {
            mBody.addView(text(c.title != null ? c.title : "", 18, 0xFFCCCCCC));
            mBody.addView(text(c.options.get(0).getTitle(), 18, Color.WHITE));
        } else {
            mBody.addView(row(c.title, selectedText(c), true, () -> {
                mPages.add(new Page(PAGE_CATEGORY, c.title, null, c));
                mVersion++;
                render();
            }));
        }
    }

    private static CharSequence selectedText(OptionCategory c) {
        if (c.type != OptionCategory.TYPE_RADIO_LIST && c.type != OptionCategory.TYPE_CHECKBOX_LIST) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (OptionItem o : c.options) {
            if (o.isSelected() && o.getTitle() != null) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(o.getTitle());
            }
        }
        return sb.length() > 0 ? sb : null;
    }

    // the items of one list
    private void renderItems(OptionCategory c, boolean subPage) {
        if (c.type == OptionCategory.TYPE_LONG_TEXT) {
            for (OptionItem o : c.options) {
                mBody.addView(text(o.getTitle(), 19, Color.WHITE));
            }
            return;
        }
        for (OptionItem o : c.options) {
            String mark = c.type == OptionCategory.TYPE_RADIO_LIST ? (o.isSelected() ? "◉  " : "○  ")
                    : c.type == OptionCategory.TYPE_CHECKBOX_LIST ? (o.isSelected() ? "☑  " : "☐  ") : "";
            mBody.addView(row(mark + (o.getTitle() != null ? o.getTitle() : ""), o.getDescription(), false, () -> {
                if (c.type == OptionCategory.TYPE_RADIO_LIST) {
                    for (OptionItem other : c.options) {
                        if (other != o) {
                            UiOptionItem.setChecked(other, false);
                        }
                    }
                    o.onSelect(true);
                } else if (c.type == OptionCategory.TYPE_CHECKBOX_LIST) {
                    boolean sel = !o.isSelected();
                    if (sel && o.getRadio() != null) {
                        for (OptionItem r : o.getRadio()) {
                            r.onSelect(false);
                        }
                    }
                    o.onSelect(sel);
                } else {
                    o.onSelect(true);
                }
            }, subPage && c.type == OptionCategory.TYPE_RADIO_LIST));
        }
    }

    private View row(CharSequence title, CharSequence sub, boolean arrow, Runnable click) {
        return row(title, sub, arrow, click, false);
    }

    /** @param popAfter radio pick inside a sub-list: go back to the dialog's main list (like the main screen) */
    private View row(CharSequence title, CharSequence sub, boolean arrow, Runnable click, boolean popAfter) {
        LinearLayout r = new LinearLayout(mCtx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(10));
        pressed.setColor(0x33FFFFFF);
        s.addState(new int[] {android.R.attr.state_pressed}, pressed);
        r.setBackground(s);
        LinearLayout texts = new LinearLayout(mCtx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title != null ? title : "", 20, Color.WHITE));
        if (sub != null && sub.length() > 0) {
            TextView st = text(sub, 16, 0xFFAAAAAA);
            st.setMaxLines(2);
            st.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(st);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (arrow) {
            r.addView(text("›", 28, 0xFFAAAAAA));
        }
        r.setOnClickListener(v -> {
            int version = mVersion;
            click.run();
            if (mVersion != version || !isShown()) {
                return; // a new dialog page was opened, or the dialog was closed by the app
            }
            if (popAfter) {
                mPages.remove(mPages.size() - 1);
                mVersion++;
            }
            render();
        });
        return r;
    }

    private TextView text(CharSequence s, float sizeDp, int color) {
        TextView t = new TextView(mCtx);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp));
        return t;
    }
}
