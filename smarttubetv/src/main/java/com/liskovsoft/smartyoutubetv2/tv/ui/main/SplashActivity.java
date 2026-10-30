package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.content.Intent;
import android.os.Bundle;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;

public class SplashActivity extends MotherActivity implements SplashView {
    private static final String TAG = SplashActivity.class.getSimpleName();
    private Intent mNewIntent;
    private SplashPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        applyCarDefaults();

        mNewIntent = getIntent();

        mPresenter = SplashPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        //finish();
    }

    /** Car build, once: keep playing (sound) when Home is pressed, resume the video on return. */
    private void applyCarDefaults() {
        android.content.SharedPreferences prefs = getSharedPreferences("car_defaults", MODE_PRIVATE);
        if (prefs.getBoolean("bg_home_sound_v1", false)) {
            return;
        }
        try {
            com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(this)
                    .setBackgroundMode(com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.BACKGROUND_MODE_SOUND);
            com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData.instance(this)
                    .setBackgroundPlaybackShortcut(com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME);
            prefs.edit().putBoolean("bg_home_sound_v1", true).apply();
        } catch (Exception e) {
            // try again next start
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        mNewIntent = intent;

        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPresenter.onViewDestroyed();
    }

    @Override
    public Intent getNewIntent() {
        return mNewIntent;
    }

    @Override
    public void finishView() {
        try {
            finish();
        } catch (NullPointerException e) {
            // NullPointerException: Attempt to invoke virtual method 'void com.android.server.wm.DisplayContent.moveStack(com.android.server.wm.TaskStack, boolean)'
            e.printStackTrace();
        }
    }
}
