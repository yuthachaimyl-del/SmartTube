package com.liskovsoft.smartyoutubetv2.common.car;

/** Car build: small shared flags between the player UI and the playback controllers. */
public final class CarState {
    /** True while the video plays on the second screen: the main screen keeps the controls up. */
    public static volatile boolean sKeepControls;

    private CarState() {
    }
}
