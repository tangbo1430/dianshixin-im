package com.tencent.bugly.crashreport;

import android.content.Context;

/** Offline build compatibility for the optional Bugly SDK. */
public final class CrashReport {
    private CrashReport() {}

    public static final class UserStrategy {
        public UserStrategy(Context context) {}
        public void setAppVersion(String version) {}
        public void setDeviceModel(String model) {}
    }

    public static void initCrashReport(Context context, String appId, boolean debug, UserStrategy strategy) {}
}
