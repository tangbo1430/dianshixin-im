package com.qchm.xyz.imaccess;

import android.content.Context;

import com.qchm.xyz.TIMAppService;
import com.qchm.xyz.blocklist.LegacyTencentBlocklistCleaner;
import com.qchm.xyz.config.AppConfig;
import com.qchm.xyz.utils.TUIUtils;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.interfaces.TUILoginConfig;

public class TencentImLoginGateway implements ImLoginGateway {
    @Override
    public void login(Context context, ImSession session, Callback callback) {
        AppConfig.DEMO_SDK_APPID = session.getSdkAppId();
        TIMAppService.getInstance().initBeforeLogin(session.getSdkAppId());
        TUILoginConfig config = TUIUtils.getLoginConfig();
        config.setInitLocalStorageOnly(false);
        TUILogin.login(context, session.getSdkAppId(), session.getImUserId(), session.getUserSig(),
                config, new TUICallback() {
                    @Override
                    public void onError(int code, String desc) {
                        callback.onError(code, desc);
                    }

                    @Override
                    public void onSuccess() {
                        LegacyTencentBlocklistCleaner.clean();
                        callback.onSuccess();
                    }
                });
    }

    @Override
    public void openLocal(Context context, ImSession session, Callback callback) {
        AppConfig.DEMO_SDK_APPID = session.getSdkAppId();
        TIMAppService.getInstance().initBeforeLogin(session.getSdkAppId());
        TUILoginConfig config = TUIUtils.getLoginConfig();
        config.setInitLocalStorageOnly(true);
        TUILogin.login(context, session.getSdkAppId(), session.getImUserId(), "", config,
                new TUICallback() {
                    @Override
                    public void onError(int code, String desc) {
                        callback.onError(code, desc);
                    }

                    @Override
                    public void onSuccess() {
                        callback.onSuccess();
                    }
                });
    }
}
