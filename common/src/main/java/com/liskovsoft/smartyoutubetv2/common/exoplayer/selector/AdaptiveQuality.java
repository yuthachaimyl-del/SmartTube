package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;

import java.lang.ref.WeakReference;

/**
 * Car build: "Auto" video quality that follows the network speed (like the official YouTube app).
 * When enabled the video renderer gets no fixed override and ExoPlayer's AdaptiveTrackSelection
 * switches bitrate on its own. Picking a fixed quality in the player dialog turns it off.
 */
public final class AdaptiveQuality {
    private static final String PREFS = "car_adaptive_quality";
    private static final String KEY_ENABLED = "enabled";
    /** Upper limit for the car screen (1920x720): no point fetching 4K. */
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;

    private static boolean sEnabled = true;
    private static boolean sLoaded;
    private static SharedPreferences sPrefs;
    private static WeakReference<DefaultTrackSelector> sSelector = new WeakReference<>(null);

    private AdaptiveQuality() {
    }

    public static void init(Context context) {
        if (sLoaded || context == null) {
            return;
        }
        sPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sEnabled = sPrefs.getBoolean(KEY_ENABLED, true);
        sLoaded = true;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        if (sPrefs != null) {
            sPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
        if (enabled) {
            apply();
        }
    }

    /** Remembers the active player's selector so the dialog can switch to auto without reloading. */
    public static void attach(DefaultTrackSelector selector) {
        sSelector = new WeakReference<>(selector);
    }

    /** Drops the fixed video override so the adaptive selection takes over right away. */
    public static void apply() {
        DefaultTrackSelector selector = sSelector.get();
        if (selector != null) {
            selector.setParameters(selector.buildUponParameters()
                    .clearSelectionOverrides(TrackSelectorManager.RENDERER_INDEX_VIDEO));
        }
    }
}
