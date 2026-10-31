package com.liskovsoft.smartyoutubetv2.common.car;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Car build: with auto quality, use only video codecs the head unit decodes in hardware
 * (like the YouTube app). A codec decoded in software (often AV1 on car units) starts slower,
 * drops frames and keeps the CPU busy, so the whole app feels slow.
 */
public final class CarCodecs {
    private static final Map<String, Boolean> sHardware = new HashMap<>();

    private CarCodecs() {
    }

    public static synchronized boolean hasHardwareDecoder(String mime) {
        if (mime == null) {
            return true;
        }
        Boolean cached = sHardware.get(mime);
        if (cached != null) {
            return cached;
        }
        boolean hw = false;
        boolean any = false;
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo info : infos) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) {
                        any = true;
                        if (isHardware(info)) {
                            hw = true;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            hw = true; // can't tell: don't filter
        }
        boolean result = hw || !any;
        sHardware.put(mime, result);
        CarPerf.log("decoder for " + mime + ": " + (any ? (hw ? "hardware" : "software only") : "none"));
        return result;
    }

    private static final Map<String, Boolean> sSmooth = new HashMap<>();

    /**
     * Whether the unit's hardware decoder for this codec says it keeps up with this picture size and frame rate
     * (its performance points, Android 10+; else its declared size/rate limits). Null when it can't tell.
     */
    public static synchronized Boolean supportsSmooth(String mime, int width, int height, float fps) {
        if (mime == null || width <= 0 || height <= 0) {
            return null;
        }
        int rate = Math.max(1, Math.round(fps > 0 ? fps : 30));
        String key = mime + " " + width + "x" + height + "@" + rate;
        if (sSmooth.containsKey(key)) {
            return sSmooth.get(key);
        }
        Boolean result = null;
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !isHardware(info) || !supportsType(info, mime)) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(mime).getVideoCapabilities();
                if (video == null) {
                    continue;
                }
                boolean ok;
                if (Build.VERSION.SDK_INT >= 29 && video.getSupportedPerformancePoints() != null
                        && !video.getSupportedPerformancePoints().isEmpty()) {
                    MediaCodecInfo.VideoCapabilities.PerformancePoint need =
                            new MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, rate);
                    ok = false;
                    for (MediaCodecInfo.VideoCapabilities.PerformancePoint point : video.getSupportedPerformancePoints()) {
                        if (point.covers(need)) {
                            ok = true;
                            break;
                        }
                    }
                } else {
                    ok = video.areSizeAndRateSupported(width, height, rate)
                            || video.areSizeAndRateSupported(height, width, rate);
                }
                result = result == null ? ok : (result || ok);
            }
        } catch (Throwable e) {
            result = null;
        }
        sSmooth.put(key, result);
        if (Boolean.FALSE.equals(result)) {
            CarPerf.log("decoder for " + mime + " can't keep up with " + key.substring(mime.length() + 1));
        }
        return result;
    }

    private static boolean supportsType(MediaCodecInfo info, String mime) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated() && !info.isSoftwareOnly();
        }
        String n = info.getName().toLowerCase(Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")
                || n.contains(".sw.") || n.endsWith(".sw"));
    }

    static String mimeOf(Format f) {
        String mime = f.sampleMimeType;
        if (mime == null && f.codecs != null) {
            mime = MimeTypes.getVideoMediaMimeType(f.codecs);
        }
        return mime;
    }

    /**
     * Format support with the software-only video codecs marked unsupported, or null when that
     * changes nothing or would leave no playable video.
     */
    public static int[][] preferHardware(TrackGroupArray groups, int[][] formatSupports) {
        if (groups == null || formatSupports == null) {
            return null;
        }
        int[][] out = new int[formatSupports.length][];
        boolean changed = false;
        boolean anyLeft = false;
        Set<String> skipped = new LinkedHashSet<>();
        for (int g = 0; g < formatSupports.length; g++) {
            out[g] = formatSupports[g].clone();
            if (g >= groups.length) {
                continue;
            }
            TrackGroup group = groups.get(g);
            for (int t = 0; t < out[g].length && t < group.length; t++) {
                int support = out[g][t] & RendererCapabilities.FORMAT_SUPPORT_MASK;
                if (support != RendererCapabilities.FORMAT_HANDLED) {
                    continue;
                }
                String mime = mimeOf(group.getFormat(t));
                if (mime != null && MimeTypes.isVideo(mime) && !hasHardwareDecoder(mime)) {
                    out[g][t] = RendererCapabilities.FORMAT_UNSUPPORTED_SUBTYPE;
                    changed = true;
                    skipped.add(mime);
                } else {
                    anyLeft = true;
                }
            }
        }
        if (!changed || !anyLeft) {
            return null;
        }
        CarPerf.log("auto quality: skipping software-decoded " + skipped);
        return out;
    }
}
