package com.liskovsoft.smartyoutubetv2.common.car;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Car build: what the driver sees when something fails.
 * <p>
 * The original app shows the raw error ("IllegalStateException: java.net.UnknownHostException: Unable to resolve
 * host ... RetrofitHelper.java:127"). In the car the usual cause is simply no or weak signal, so show one short,
 * friendly line instead, keep the technical details in the log, and reload by itself when the signal comes back.
 */
public final class CarErrors {
    private static final String TAG = "CarErrors";
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final List<Runnable> sWaiting = new ArrayList<>();
    private static ConnectivityManager.NetworkCallback sCallback;

    private CarErrors() {
    }

    // ---------------------------------------------------------------- messages

    /** "No internet" when that's the cause (or the device is offline), otherwise a short generic line. */
    public static String text(@Nullable Context context, @Nullable Throwable error) {
        if (error != null) {
            Log.w(TAG, "error shown to the user", error);
        }
        if (isNetwork(error) || (context != null && !isOnline(context))) {
            return noInternet(context);
        }
        return isThai(context) ? "โหลดไม่สำเร็จ กำลังลองใหม่…" : "Couldn't load. Trying again…";
    }

    /**
     * A message that may contain technical text: technical text becomes a friendly line, anything else (a normal
     * sentence) is kept.
     */
    public static String clean(@Nullable Context context, @Nullable String message) {
        if (message == null || !looksTechnical(message)) {
            return message;
        }
        Log.w(TAG, "message shown to the user: " + message);
        if (looksLikeNetwork(message) || (context != null && !isOnline(context))) {
            return noInternet(context);
        }
        return isThai(context) ? "โหลดไม่สำเร็จ กำลังลองใหม่…" : "Couldn't load. Trying again…";
    }

    public static String noInternet(@Nullable Context context) {
        return isThai(context)
                ? "กำลังรออินเทอร์เน็ต… จะโหลดให้เองเมื่อต่อได้"
                : "Waiting for the internet… will load by itself once connected";
    }

    public static boolean isNetwork(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof SocketTimeoutException
                    || t instanceof NoRouteToHostException || t instanceof SocketException
                    || t instanceof InterruptedIOException || t instanceof javax.net.ssl.SSLException) {
                return true;
            }
            if (looksLikeNetwork(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeNetwork(@Nullable String m) {
        if (m == null) {
            return false;
        }
        String s = m.toLowerCase(Locale.ROOT);
        return s.contains("unable to resolve host") || s.contains("unable to connect") || s.contains("failed to connect")
                || s.contains("no address associated") || s.contains("network is unreachable") || s.contains("timeout")
                || s.contains("timed out") || s.contains("connection reset") || s.contains("connection abort")
                || s.contains("software caused connection") || s.contains("unknownhost") || s.contains("cronet");
    }

    private static boolean looksTechnical(String m) {
        return m.contains("Exception") || m.contains("Error:") || m.contains(".java:") || m.contains("java.")
                || m.contains("http://") || m.contains("https://") || m.contains("Response code") || m.contains("\tat ")
                || looksLikeNetwork(m);
    }

    /** Car build messages are in Thai, like the rest of the car additions. */
    private static boolean isThai(@Nullable Context context) {
        return true;
    }

    // ---------------------------------------------------------------- connectivity

    /** Result of {@link #probe}. */
    public interface Probe {
        void done(boolean alive);
    }

    /**
     * Is anything actually reachable? The system says "online" in a tunnel or a dead zone too (the mobile network is
     * still attached); a tiny request with a short timeout tells. Answers on the main thread within ~5 s.
     */
    public static void probe(Probe callback) {
        Thread t = new Thread(() -> {
            boolean alive = false;
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL("https://connectivitycheck.gstatic.com/generate_204").openConnection();
                c.setConnectTimeout(4_000);
                c.setReadTimeout(4_000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Connection", "close");
                alive = c.getResponseCode() > 0;
            } catch (Throwable e) {
                Log.w(TAG, "probe: nothing reachable: " + e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            boolean result = alive;
            sMain.post(() -> callback.done(result));
        }, "car-net-probe");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities c = n != null ? cm.getNetworkCapabilities(n) : null;
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return true; // can't tell: don't claim we're offline
        }
    }

    /**
     * Runs {@code r} on the main thread once the internet is back (right away if it already is). The same runnable is
     * queued only once.
     */
    public static synchronized void whenOnline(Context context, Runnable r) {
        if (context == null || r == null) {
            return;
        }
        if (isOnline(context)) {
            return; // online already: the caller's own retry takes care of it
        }
        if (!sWaiting.contains(r)) {
            sWaiting.add(r);
        }
        if (sCallback != null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            sCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        back(cm);
                    }
                }

                @Override
                public void onAvailable(Network network) {
                    sMain.postDelayed(() -> {
                        if (isOnline(context.getApplicationContext())) {
                            back(cm);
                        }
                    }, 1500);
                }
            };
            cm.registerDefaultNetworkCallback(sCallback);
        } catch (Exception e) {
            sCallback = null;
        }
    }

    private static synchronized void back(ConnectivityManager cm) {
        if (sCallback == null) {
            return;
        }
        try {
            cm.unregisterNetworkCallback(sCallback);
        } catch (Exception ignored) {
        }
        sCallback = null;
        final List<Runnable> run = new ArrayList<>(sWaiting);
        sWaiting.clear();
        Log.i(TAG, "internet back: reloading " + run.size());
        sMain.post(() -> {
            for (Runnable x : run) {
                try {
                    x.run();
                } catch (Exception e) {
                    Log.w(TAG, "reload failed", e);
                }
            }
        });
    }
}
