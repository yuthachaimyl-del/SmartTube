package com.liskovsoft.smartyoutubetv2.tv.ui.dialogs;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.fragment.app.Fragment;

import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.GlobalKeyTranslator;
import com.liskovsoft.smartyoutubetv2.common.misc.PlayerKeyTranslator;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackActivity;

public class AppDialogActivity extends MotherActivity {
    private static final String TAG = AppDialogActivity.class.getSimpleName();
    private AppDialogFragment mFragment;
    private GlobalKeyTranslator mGlobalKeyTranslator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_app_settings);
        // Can't use getSupportFragmentManager because AppDialogFragment isn't subclass of androidx fragment
        mFragment = (AppDialogFragment) getFragmentManager().findFragmentById(R.id.app_settings_fragment);

        mGlobalKeyTranslator = new PlayerKeyTranslator(this);
        mGlobalKeyTranslator.apply();
    }

    @Override
    protected void initTheme() {
        int settingsThemeResId = MainUIData.instance(this).getColorScheme().settingsThemeResId;
        if (settingsThemeResId > 0) {
            setTheme(settingsThemeResId);
        }
    }

    private void setupActivity() {
        // Fix crash in AppSettingsActivity: "Only fullscreen opaque activities can request orientation"
        // Error happen only on Android O (api 26) when you set "portrait" orientation in manifest
        // So, to fix the problem, set orientation here instead of manifest
        // More info: https://stackoverflow.com/questions/48072438/java-lang-illegalstateexception-only-fullscreen-opaque-activities-can-request-o
        if (VERSION.SDK_INT != 26) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        //if (KeyHelpers.isConfirmKey(event.getKeyCode()) && !event.isLongPress()) {
        //    return true;
        //}

        //return mGlobalKeyTranslator.translate(event) || super.dispatchKeyEvent(event);
        KeyEvent newEvent = mGlobalKeyTranslator.translate(event);
        return handleNavigation(newEvent) || super.dispatchKeyEvent(newEvent);
    }
    
    private boolean handleNavigation(KeyEvent event) {
        if (event == null) {
            return false;
        }

        // Toggle dialog
        if (!mFragment.isOverlay() && (KeyHelpers.isLeftRightKey(event.getKeyCode()) || KeyHelpers.isMenuKey(event.getKeyCode()))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                finish();
            }
            return true;
        }

        // Notification dialog type. Imitate notification behavior.
        if (mFragment.isOverlay() && (KeyHelpers.isNavigationKey(event.getKeyCode()) || KeyHelpers.isMenuKey(event.getKeyCode()))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                finish();
            }
            PlaybackView view = PlaybackPresenter.instance(this).getView();
            if (view instanceof Fragment) {
                Activity activity = ((Fragment) view).getActivity();
                if (activity != null) {
                    activity.dispatchKeyEvent(event);
                }
            }
            return true;
        }

        return false;
    }

    // Car build: tapping anywhere outside the dialog panel closes it (like a phone app)
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && isOutsidePanel(event)) {
            finish();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isOutsidePanel(android.view.MotionEvent event) {
        android.view.View root = mFragment != null ? mFragment.getView() : null;
        if (root == null || root.getWidth() == 0) {
            return false;
        }
        android.view.View panel = findPanel(root, root.getWidth(), root.getHeight());
        if (panel == null) {
            return false;
        }
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        float x = event.getRawX(), y = event.getRawY();
        boolean inside = x >= loc[0] && x <= loc[0] + panel.getWidth() && y >= loc[1] && y <= loc[1] + panel.getHeight();
        if (inside) {
            return false;
        }
        // keep the on-screen Back button working
        android.view.View back = getWindow().getDecorView().findViewWithTag("car_back_button");
        if (back != null && back.isShown()) {
            back.getLocationOnScreen(loc);
            if (x >= loc[0] && x <= loc[0] + back.getWidth() && y >= loc[1] && y <= loc[1] + back.getHeight()) {
                return false;
            }
        }
        return true;
    }

    /** The visible dialog panel: the largest visible view that is narrower than the screen. */
    private static android.view.View findPanel(android.view.View v, int rootW, int rootH) {
        if (v.getVisibility() != android.view.View.VISIBLE) {
            return null;
        }
        if (v.getWidth() > 0 && v.getWidth() < rootW * 0.9f && v.getHeight() > rootH * 0.3f) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                android.view.View found = findPanel(g.getChildAt(i), rootW, rootH);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    public void finish() {
        // NOTE: Fragment's onDestroy/onDestroyView are not reliable way to catch dialog finish
        Log.d(TAG, "Dialog finish");
        if (mFragment != null) { // fragment isn't created yet (expandable = true)
            // Fix touchscreen/mouse DPAD emulation on API 28+
            // The bug on API 28+ when using touchscreen/mouse to traverse nested dialog categories.
            // In this case the back gesture closes dialog instead of return to the previous category.
            // Cause: touchscreen/mouse input flips the view hierarchy into touch mode, which back-handling
            // treats differently than real D-pad input.
            if (isBackPressed() && mFragment.canGoBack()) {
                mFragment.goBack();
                // No additional event like onResume is fired, so we should reset the state explicitly
                resetBackState();
                return;
            }

            mFragment.onFinish();
        }

        // Destroy dialog when BACK is pressed. NoHistory isn't reliable if combined with singleInstance
        finishReally();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // Respect PIP mode
        if (ViewManager.instance(this).getTopView() == PlaybackView.class && PlaybackPresenter.instance(this).getContext() instanceof PlaybackActivity) {
            ((PlaybackActivity) PlaybackPresenter.instance(this).getContext()).onUserLeaveHint();
        }

        finish();
    }
}
