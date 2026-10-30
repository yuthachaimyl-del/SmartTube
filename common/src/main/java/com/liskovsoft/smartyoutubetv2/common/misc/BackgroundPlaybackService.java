package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * This service isn't used at all! It can be safely removed in the future.
 */
public class BackgroundPlaybackService extends Service {
    private static final String TAG = BackgroundPlaybackService.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        // Car build: keeps the app at full priority while it plays sound in the background
        // (the head unit throttles background apps -> the sound stutters).
        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "car_background_sound";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "เล่นเสียงเบื้องหลัง",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle("SmartTube")
                    .setContentText("กำลังเล่นเสียง")
                    .setOngoing(true)
                    .build();

            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(1, notification);
                }
            } catch (Exception e) {
                Log.e(TAG, "startForeground: %s", e.getMessage());
                stopSelf();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: %s", Helpers.toString(intent));

        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: %s", Helpers.toString(intent));

        return super.onStartCommand(intent, flags, startId);
    }

    public static void start(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            // Fake service to prevent the app from destroying
            Intent serviceIntent = new Intent(context, BackgroundPlaybackService.class);
            try {
                context.startForegroundService(serviceIntent);
            } catch (Exception e) { // not allowed from the background on new Android versions
                Log.e(TAG, "start: %s", e.getMessage());
            }
        }
    }

    public static void stop(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            // Fake service to prevent the app from destroying
            Intent serviceIntent = new Intent(context, BackgroundPlaybackService.class);
            try {
                context.stopService(serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "stop: %s", e.getMessage());
            }
        }
    }
}
