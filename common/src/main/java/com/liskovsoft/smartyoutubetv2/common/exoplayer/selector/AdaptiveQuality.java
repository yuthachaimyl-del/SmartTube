package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.liskovsoft.smartyoutubetv2.common.car.CarAutoQuality;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;

import java.lang.ref.WeakReference;

/**
 * Car build: "Auto" picture and sound (see {@link CarAutoQuality}).
 * Picking a fixed video quality (or a preset) in the player turns auto picture off, picking a fixed audio format
 * turns auto sound off; choosing "Auto" again turns it back on.
 */
public final class AdaptiveQuality {
    private static final String PREFS = "car_adaptive_quality";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_AUDIO = "audio_auto";
    /** Upper limit for the car screens (1920x720, 2400x896): no point fetching 4K. */
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;

    private static boolean sEnabled = true;
    private static boolean sAudioAuto = true;
    private static boolean sLoaded;
    private static SharedPreferences sPrefs;
    private static WeakReference<DefaultTrackSelector> sSelector = new WeakReference<>(null);

    private AdaptiveQuality() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        CarAutoQuality.init(context);
        if (sLoaded) {
            return;
        }
        sPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sEnabled = sPrefs.getBoolean(KEY_ENABLED, true);
        sAudioAuto = sPrefs.getBoolean(KEY_AUDIO, true);
        sLoaded = true;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void setEnabled(boolean enabled) {
        boolean changed = sEnabled != enabled;
        sEnabled = enabled;
        if (sPrefs != null) {
            sPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        }
        if (enabled) {
            if (changed) {
                CarAutoQuality.resetLearned(); // chosen again: try the best levels again
            }
            apply();
        }
    }

    /** Auto sound: the best original stream, nothing applied to it. */
    public static boolean isAudioAuto() {
        return sAudioAuto;
    }

    public static void setAudioAuto(boolean enabled) {
        sAudioAuto = enabled;
        if (sPrefs != null) {
            sPrefs.edit().putBoolean(KEY_AUDIO, enabled).apply();
        }
        if (enabled) {
            DefaultTrackSelector selector = sSelector.get();
            if (selector != null) {
                selector.setParameters(selector.buildUponParameters()
                        .clearSelectionOverrides(TrackSelectorManager.RENDERER_INDEX_AUDIO));
                reselect();
            }
        }
    }

    /** Remembers the active player's selector so the dialog can switch to auto without reloading. */
    public static void attach(DefaultTrackSelector selector) {
        sSelector = new WeakReference<>(selector);
    }

    /** Drops the fixed video override so the auto choice takes over right away. */
    public static void apply() {
        DefaultTrackSelector selector = sSelector.get();
        if (selector != null) {
            selector.setParameters(selector.buildUponParameters()
                    .clearSelectionOverrides(TrackSelectorManager.RENDERER_INDEX_VIDEO));
            reselect();
        }
    }

    /** Runs the auto choice again (e.g. after a step down). */
    public static void reselect() {
        DefaultTrackSelector selector = sSelector.get();
        if (selector instanceof RestoreTrackSelector) {
            ((RestoreTrackSelector) selector).reselect();
        }
    }
}
