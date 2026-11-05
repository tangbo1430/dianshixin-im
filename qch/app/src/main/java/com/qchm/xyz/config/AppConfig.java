package com.qchm.xyz.config;

import com.qchm.xyz.utils.Constants;

public class AppConfig {
    public static final int DEMO_UI_STYLE_CLASSIC = 0;
    public static final int DEMO_UI_STYLE_MINIMALIST = 1;

    // app flavor
    public static String DEMO_FLAVOR_VERSION = Constants.FLAVOR_LOCAL;

    public static int DEMO_UI_STYLE = DEMO_UI_STYLE_CLASSIC;
    // long connection addr: china、india ...
    public static int DEMO_TEST_ENVIRONMENT = 0;
    // Legacy SDKAppID: 1600088222. Runtime login uses /imaccess/session as the source of truth.
    public static int DEMO_SDK_APPID = 20044723;
    public static final String RT_CUBE_PACKAGE_NAME = "com.tencent.trtc";
}
