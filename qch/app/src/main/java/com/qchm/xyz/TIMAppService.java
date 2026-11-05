package com.qchm.xyz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.auto.service.AutoService;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.LoginActivity;
import com.qchm.xyz.config.AppConfig;
import com.qchm.xyz.config.InitSetting;
import com.qchm.xyz.custom.CustomConfigHelper;
import com.qchm.xyz.main.MainActivity;
import com.qchm.xyz.main.MainMinimalistActivity;
import com.qchm.xyz.imaccess.ImLoginCoordinator;
import com.qchm.xyz.imaccess.ImNetworkMonitor;
import com.qchm.xyz.imaccess.ImSession;
import com.qchm.xyz.imaccess.ImSessionException;
import com.qchm.xyz.blocklist.AppBlocklistRepository;
import com.qchm.xyz.utils.Constants;
import com.qchm.xyz.utils.DemoLog;
import com.tencent.qcloud.tuicore.ServiceInitializer;
import com.tencent.qcloud.tuicore.TUIConstants;
import com.tencent.qcloud.tuicore.TUICore;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.annotations.TUIInitializerDependency;
import com.tencent.qcloud.tuicore.annotations.TUIInitializerID;
import com.tencent.qcloud.tuicore.interfaces.ITUIService;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.interfaces.TUIInitializer;
import com.tencent.qcloud.tuicore.interfaces.TUIServiceCallback;
import com.tencent.qcloud.tuicore.interfaces.TUILoginListener;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuikit.tuichat.presenter.ChatPresenter;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

@AutoService(TUIInitializer.class)
@TUIInitializerDependency("TIMCommon")
@TUIInitializerID("TIMAppService")
public class TIMAppService  extends ServiceInitializer implements TUIInitializer, ITUIService {
    public static final String TAG = TIMAppService.class.getSimpleName();
    private static TIMAppService instance;

    public static TIMAppService getInstance() {
        return instance;
    }

    public Context mContext;

    private BroadcastReceiver languageChangedReceiver;
    private BroadcastReceiver themeChangedReceiver;

    private InitSetting initSetting;
    private AppBlocklistRepository blocklistRepository;
    private boolean imAccessStateListenerRegistered;

    private final ImLoginCoordinator.StateListener imAccessStateListener = state -> {
        if (state == ImLoginCoordinator.State.AUTH_EXPIRED) {
            ToastUtil.toastLongMessage("登录已失效，请重新登录");
            logout();
        } else if (state == ImLoginCoordinator.State.ONLINE) {
            ChatPresenter.syncVisibleChatsAfterReconnect();
        }
    };

    @Override
    public void init(Context context) {
        instance = this;
        mContext = context;
        blocklistRepository = new AppBlocklistRepository();
        TUICore.registerService(TUIConstants.Service.TUI_DEMO, this);

        initSetting = new InitSetting(mContext);
        initSetting.init();
        initThemeAndLanguageChangedReceiver();
        initLoginStatusListener();
        if (!imAccessStateListenerRegistered) {
            ImLoginCoordinator.getInstance().addStateListener(imAccessStateListener);
            imAccessStateListenerRegistered = true;
        }
        ImNetworkMonitor.getInstance().start(context);
    }

    @Override
    public Object onCall(String method, Map<String, Object> param, TUIServiceCallback callback) {
        if (blocklistRepository == null) {
            blocklistRepository = new AppBlocklistRepository();
        }
        if (TUIConstants.AppBlocklist.METHOD_LIST.equals(method)) {
            blocklistRepository.listing(serviceCallback(callback, TUIConstants.AppBlocklist.METHOD_LIST));
            return null;
        }

        String peerAccount = param == null ? "" : String.valueOf(param.get(TUIConstants.AppBlocklist.PARAM_PEER_ACCOUNT));
        if (peerAccount == null || peerAccount.trim().isEmpty() || "null".equals(peerAccount)) {
            notifyServiceError(callback, 400, "用户账号无效");
            return null;
        }
        if (TUIConstants.AppBlocklist.METHOD_STATUS.equals(method)) {
            blocklistRepository.status(peerAccount, serviceCallback(callback, TUIConstants.AppBlocklist.METHOD_STATUS));
        } else if (TUIConstants.AppBlocklist.METHOD_CHANGE.equals(method)) {
            boolean blocked = param != null && Boolean.TRUE.equals(param.get(TUIConstants.AppBlocklist.PARAM_BLOCKED));
            blocklistRepository.change(peerAccount, blocked, serviceCallback(callback, TUIConstants.AppBlocklist.METHOD_CHANGE));
        } else {
            notifyServiceError(callback, 404, "不支持的屏蔽操作");
        }
        return null;
    }

    private AppBlocklistRepository.Callback serviceCallback(TUIServiceCallback callback, String method) {
        return new AppBlocklistRepository.Callback() {
            @Override
            public void onSuccess(JSONObject response) {
                Bundle bundle = new Bundle();
                if (TUIConstants.AppBlocklist.METHOD_LIST.equals(method)) {
                    JSONArray list = response.optJSONArray("data");
                    bundle.putString(TUIConstants.AppBlocklist.RESULT_LIST_JSON, list == null ? "[]" : list.toString());
                } else {
                    JSONObject data = response.optJSONObject("data");
                    bundle.putBoolean(TUIConstants.AppBlocklist.RESULT_BLOCKED, data != null && data.optBoolean("blocked", false));
                }
                if (callback != null) {
                    callback.onServiceCallback(0, "", bundle);
                }
            }

            @Override
            public void onError(int code, String message) {
                notifyServiceError(callback, code, message);
            }
        };
    }

    private void notifyServiceError(TUIServiceCallback callback, int code, String message) {
        if (callback != null) {
            callback.onServiceCallback(code, message, new Bundle());
        }
    }

    public void initLoginStatusListener() {
        TUILogin.addLoginListener(loginStatusListener);
    }

    private final TUILoginListener loginStatusListener = new TUILoginListener() {
        @Override
        public void onKickedOffline() {
            ToastUtil.toastLongMessage(getAppContext().getString(R.string.repeat_login_tip));
            logout();
        }

        @Override
        public void onUserSigExpired() {
            ToastUtil.toastLongMessage(getAppContext().getString(R.string.expired_login_tip));
            ImLoginCoordinator.getInstance().refreshAfterExpiry(getAppContext(), new ImLoginCoordinator.Callback() {
                @Override
                public void onSuccess(ImSession session) {}

                @Override
                public void onError(ImSessionException error) {
                    if (!error.isAuthenticationFailure()) {
                        ToastUtil.toastLongMessage("网络暂不可用，已保留本地聊天记录");
                    }
                }
            });
        }
    };

    public void logout() {
        DemoLog.i(TAG, "logout");
        ChatPresenter.clearTransientState();
        ImLoginCoordinator.getInstance().clearSessionState();
        TUILogin.logout((TUICallback) null);
        UserInfo.getInstance().cleanUserInfo();

        Intent intent = new Intent(getAppContext(), LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("LOGOUT", true);
        getAppContext().startActivity(intent);

        if (AppConfig.DEMO_UI_STYLE == AppConfig.DEMO_UI_STYLE_CLASSIC) {
            MainActivity.finishMainActivity();
        } else {
            MainMinimalistActivity.finishMainActivity();
        }
    }

    private void initThemeAndLanguageChangedReceiver() {
        languageChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                initSetting.setPermissionRequestContent();
            }
        };

        themeChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                CustomConfigHelper.initConversationDefaultAvatar();
            }
        };
        IntentFilter languageFilter = new IntentFilter();
        languageFilter.addAction(Constants.DEMO_LANGUAGE_CHANGED_ACTION);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(languageChangedReceiver, languageFilter);

        IntentFilter themeFilter = new IntentFilter();
        themeFilter.addAction(Constants.DEMO_THEME_CHANGED_ACTION);
        LocalBroadcastManager.getInstance(mContext).registerReceiver(themeChangedReceiver, themeFilter);
    }

    public void registerPushManually() {
        if (!BuildConfig.ENABLE_TIM_PUSH) {
            return;
        }
        if (initSetting == null) {
            initSetting = new InitSetting(mContext);
        }

        initSetting.registerPushManually();
    }

    public void initBeforeLogin(int sdkappid) {
        if (initSetting == null) {
            initSetting = new InitSetting(mContext);
        }

        initSetting.initBeforeLogin(sdkappid);
    }

    public void startLoginActivity() {
        Intent intent = new Intent(mContext, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    public static Context getAppContext() {
        return ServiceInitializer.getAppContext();
    }
}
