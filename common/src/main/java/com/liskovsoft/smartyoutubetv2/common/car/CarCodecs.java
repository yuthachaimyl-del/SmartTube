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

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated() && !info.isSoftwareOnly();
        }
        String n = info.getName().toLowerCase(Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.")
                || n.contains(".sw.") || n.endsWith(".sw"));
    }

    private static String mimeOf(Format f) {
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
