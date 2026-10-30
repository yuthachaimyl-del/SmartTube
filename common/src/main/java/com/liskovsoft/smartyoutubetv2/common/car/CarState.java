package com.liskovsoft.smartyoutubetv2.common.car;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;

import java.util.List;

/** Car build: small shared flags between the player UI and the playback controllers. */
public final class CarState {
    /** True while the video plays on the second screen: the main screen keeps the controls up. */
    public static volatile boolean sKeepControls;

    /** The second screen shows the app's option dialogs (quality, subtitles, ...) when they are asked from there. */
    public interface DialogSink {
        boolean isOpen();
        void show(CharSequence title, List<OptionCategory> categories, Runnable onClosed);
        void goBack();
        void close();
    }

    public static volatile DialogSink sDialogSink;
    public static volatile long sDialogArmUntil;

    /** The next dialog within {@code ms} was asked from the second screen. */
    public static void armDialog(long ms) {
        sDialogArmUntil = System.currentTimeMillis() + ms;
    }

    public static boolean isDialogArmed() {
        return System.currentTimeMillis() < sDialogArmUntil;
    }

    /** Chat and comments need the full dialog: those stay on the main screen. */
    public static boolean canShowOnSink(List<OptionCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        for (OptionCategory c : categories) {
            if (c == null || c.type == OptionCategory.TYPE_CHAT || c.type == OptionCategory.TYPE_COMMENTS) {
                return false;
            }
        }
        return true;
    }

    private CarState() {
    }
}
