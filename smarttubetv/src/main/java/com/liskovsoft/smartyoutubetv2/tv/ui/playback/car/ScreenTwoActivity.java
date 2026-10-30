package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.app.Activity;
import android.os.Bundle;

/**
 * Car build: the video's own app screen on the second display (like the YouTube app there),
 * so it can go full screen and hide that display's toolbar. Started and filled by {@link DualScreen}.
 */
public final class ScreenTwoActivity extends Activity {
    private DualScreen mOwner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOwner = DualScreen.sCurrent;
        DualScreen.ScreenTwoUi ui = mOwner != null ? mOwner.attachActivity(this) : null;
        if (ui == null) {
            mOwner = null;
            finish();
            return;
        }
        setContentView(ui.build(this));
        DualScreen.goFullScreen(getWindow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        DualScreen.goFullScreen(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            DualScreen.goFullScreen(getWindow());
        }
    }

    @Override
    public void onBackPressed() {
        finish();   // Back on the second screen: video back to the main screen only
    }

    @Override
    protected void onDestroy() {
        if (mOwner != null) {
            mOwner.detachActivity(this);
            mOwner = null;
        }
        super.onDestroy();
    }
}
