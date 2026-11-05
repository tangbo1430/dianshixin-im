package com.qchm.xyz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.SPUtils;
import com.blankj.utilcode.util.ThreadUtils;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.LoginActivity;
import com.qchm.xyz.config.AppConfig;
import com.qchm.xyz.imaccess.ImLoginCoordinator;
import com.qchm.xyz.imaccess.ImSession;
import com.qchm.xyz.imaccess.ImSessionException;
import com.qchm.xyz.main.MainActivity;
import com.qchm.xyz.utils.BrandUtil;
import com.qchm.xyz.utils.Constants;
import com.qchm.xyz.utils.DemoLog;
import com.qchm.xyz.utils.PrivateConstants;
import com.qchm.xyz.utils.TUIUtils;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.bugly.crashreport.CrashReport;
import com.tencent.qcloud.tuicore.TUIConfig;
import com.tencent.qcloud.tuicore.TUIConstants;
import com.tencent.qcloud.tuicore.TUICore;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.ITUINotification;
import com.tencent.qcloud.tuicore.interfaces.ITUIObjectFactory;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

public class SplashActivity extends BaseLightActivity {

    private static final String TAG = SplashActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 21) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        // The registration-visibility probe is optional metadata and must not gate
        // the first visible screen. It used to wait for the legacy API here, so an
        // unreachable backend left users on the splash screen until OkGo timed out.
        handleData();
    }

    private void getReg() {
        String url = DemoApplication.NewAddress+"/api/login/show_reg";
        OkGo.<String>post(url)
                .tag(this)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                handleData();
                            }else {
                                Toast.makeText(SplashActivity.this, object.optString("msg", "服务暂时不可用"), Toast.LENGTH_SHORT).show();
                                handleData();
                            }
                        } catch (JSONException e) {
                            handleData();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        Toast.makeText(SplashActivity.this, "服务连接失败，正在尝试登录", Toast.LENGTH_SHORT).show();
                        handleData();
                    }
                });
    }

    private void handleData() {
        UserInfo userInfo = UserInfo.getInstance();
        if (userInfo != null && userInfo.isAutoLogin()) {
            init();
            login();
        } else {
            new LunchDialog(this, v -> {
                init();
                startLogin();
            }, v -> finish()).show();
        }
    }

    private void login() {
        ImLoginCoordinator.getInstance().autoLogin(this, new ImLoginCoordinator.Callback() {
            @Override
            public void onError(ImSessionException error) {
                ToastUtil.toastLongMessage(getString(R.string.failed_login_tip) + ": " + error.getMessage());
                DemoLog.i(TAG, "imLogin errorCode = " + error.getCode());
                startLogin();
            }

            @Override
            public void onSuccess(ImSession session) {
                startMain();
                TIMAppService.getInstance().registerPushManually();
            }
        });
    }

    private void startLogin() {
        Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
        intent.putExtras(getIntent());
        startActivity(intent);
        finish();
    }

    private void startMain() {
        DemoLog.i(TAG, "MainActivity");
        Intent intent;
        if (AppConfig.DEMO_UI_STYLE == 0) {
            intent = new Intent(SplashActivity.this, MainActivity.class);
        } else {
            intent = new Intent(SplashActivity.this, MainActivity.class);
        }
        Intent dataIntent = getIntent();
        intent.putExtras(dataIntent);
        if (dataIntent != null) {
            String ext = dataIntent.getStringExtra(TUIConstants.TUIOfflinePush.NOTIFICATION_EXT_KEY);
            intent.putExtra(TUIConstants.TUIOfflinePush.NOTIFICATION_EXT_KEY, ext);
        }
        startActivity(intent);
        finish();
    }

    private void init() {
        initBugly();
        initIMDemoAppInfo();
        setPermissionRequestContent();
        registerLanguageChangedReceiver();
    }

    private void registerLanguageChangedReceiver() {
        BroadcastReceiver languageChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                setPermissionRequestContent();
            }
        };
        IntentFilter languageFilter = new IntentFilter();
        languageFilter.addAction(Constants.DEMO_LANGUAGE_CHANGED_ACTION);
        LocalBroadcastManager.getInstance(DemoApplication.instance()).registerReceiver(languageChangedReceiver, languageFilter);
    }

    public void setPermissionRequestContent() {
        ApplicationInfo applicationInfo = DemoApplication.instance().getApplicationInfo();
        CharSequence labelCharSequence = applicationInfo.loadLabel(DemoApplication.instance().getPackageManager());
        String appName = "App";
        if (!TextUtils.isEmpty(labelCharSequence)) {
            appName = labelCharSequence.toString();
        }
        String micReason = DemoApplication.instance().getResources().getString(R.string.demo_permission_mic_reason);
        String micDeniedAlert = DemoApplication.instance().getResources().getString(R.string.demo_permission_mic_dialog_alert, appName);

        String cameraReason = DemoApplication.instance().getResources().getString(R.string.demo_permission_camera_reason);
        String cameraDeniedAlert = DemoApplication.instance().getResources().getString(R.string.demo_permission_camera_dialog_alert, appName);

        TUICore.unregisterObjectFactory(TUIConstants.Privacy.PermissionsFactory.FACTORY_NAME);
        TUICore.registerObjectFactory(TUIConstants.Privacy.PermissionsFactory.FACTORY_NAME, new ITUIObjectFactory() {
            @Override
            public Object onCreateObject(String objectName, Map<String, Object> param) {
                if (TextUtils.equals(
                        objectName, TUIConstants.Privacy.PermissionsFactory.PermissionsName.CAMERA_PERMISSIONS)) {
                    return cameraReason;
                } else if (TextUtils.equals(objectName,
                        TUIConstants.Privacy.PermissionsFactory.PermissionsName.MICROPHONE_PERMISSIONS)) {
                    return micReason;
                } else if (TextUtils.equals(objectName,
                        TUIConstants.Privacy.PermissionsFactory.PermissionsName.CAMERA_PERMISSIONS_TIP)) {
                    return cameraDeniedAlert;
                } else if (TextUtils.equals(objectName,
                        TUIConstants.Privacy.PermissionsFactory.PermissionsName.MICROPHONE_PERMISSIONS_TIP)) {
                    return micDeniedAlert;
                }
                return null;
            }
        });
    }

    private void initBugly() {
        TUICore.registerEvent(TUIConstants.TUILogin.EVENT_IMSDK_INIT_STATE_CHANGED,
                TUIConstants.TUILogin.EVENT_SUB_KEY_START_INIT, new ITUINotification() {
                    @Override
                    public void onNotifyEvent(String key, String subKey, Map<String, Object> param) {
                        CrashReport.UserStrategy strategy = new CrashReport.UserStrategy(DemoApplication.instance().getApplicationContext());
                        strategy.setAppVersion(BuildConfig.VERSION_NAME);
                        strategy.setDeviceModel(BrandUtil.getBuildModel());
                        CrashReport.initCrashReport(DemoApplication.instance().getApplicationContext(), PrivateConstants.BUGLY_APPID, true, strategy);
                    }
                });
    }

    private void initIMDemoAppInfo() {
        TUIConfig.setTUIHostType(TUIConfig.TUI_HOST_TYPE_IMAPP);
        try {
            Field field = BuildConfig.class.getField("FLAVOR");
            AppConfig.DEMO_FLAVOR_VERSION = (String) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            // ignore
        }
    }


}
