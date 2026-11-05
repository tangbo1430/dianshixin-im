package com.qchm.xyz.privacy;

import android.content.Context;
import android.content.SharedPreferences;

public final class PrivacyConsentManager {
    private static final String PREF_NAME = "qch_privacy";
    private static final String KEY_ACCEPTED = "privacy_accepted_v1";

    private PrivacyConsentManager() {
    }

    public static boolean isAccepted(Context context) {
        return getPreferences(context).getBoolean(KEY_ACCEPTED, false);
    }

    public static void markAccepted(Context context) {
        getPreferences(context).edit().putBoolean(KEY_ACCEPTED, true).apply();
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
