package com.qchm.xyz.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public final class NotificationPermissionHelper {
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 13013;
    private static final String PREF_NAME = Constants.DEMO_SETTING_SP_NAME;
    private static final String KEY_PROMPTED = "post_notifications_prompted";

    private NotificationPermissionHelper() {
    }

    public static void requestIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        SharedPreferences preferences = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        if (preferences.getBoolean(KEY_PROMPTED, false)) {
            return;
        }
        preferences.edit().putBoolean(KEY_PROMPTED, true).apply();
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_CODE_POST_NOTIFICATIONS);
    }
}
