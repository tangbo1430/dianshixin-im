package com.qchm.xyz;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import com.blankj.utilcode.util.SPUtils;

import com.tencent.qcloud.tuicore.TUIConstants;
import com.tencent.qcloud.tuicore.TUICore;
import com.tencent.qcloud.tuicore.TUIThemeManager;
import com.tencent.qcloud.tuicore.interfaces.ITUINotification;

import java.util.Map;

public class DemoApplication extends MultiDexApplication {
    private static final String TAG = "===App===";

    private static DemoApplication instance;
    public static String NewAddress = BuildConfig.API_BASE_URL;
    public static final String PRIVACY_URL = "https://www.xmjcmy.com/legal/dianshixin-ab/privacy-policy.html";
    public static final String TERMS_URL = "https://www.xmjcmy.com/legal/dianshixin-ab/user-agreement.html";

    public static DemoApplication instance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Legacy TUIKit pages read this shared value for business API calls.
        // Keep them on the same HTTPS backend as the current login/session flow.
        SPUtils.getInstance().put("Address", NewAddress);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // add language changed listener
        TUICore.registerEvent(TUIConstants.TUICore.LANGUAGE_EVENT, TUIConstants.TUICore.LANGUAGE_EVENT_SUB_KEY, new ITUINotification() {
            @Override
            public void onNotifyEvent(String key, String subKey, Map<String, Object> param) {
                TUIThemeManager.setWebViewLanguage(base);
            }
        });
    }


}
