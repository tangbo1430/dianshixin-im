package com.qchm.xyz.profile;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.blankj.utilcode.util.PermissionUtils;
import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.view.X5WebViewActivity;
import com.qchm.xyz.utils.CacheDataManager;
import com.qchm.xyz.utils.ProfileUtil;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.dialog.TUIKitDialog;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class MySetActivity extends BaseLightActivity {
    private TitleBarLayout titleBarLayout;
    private View f_huanC;
    private TextView t_huanC;
    private boolean cancelInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_self_set);

        getUserInfo();
        f_huanC = findViewById(R.id.f_huanC);
        t_huanC = findViewById(R.id.t_huanC);
        titleBarLayout = findViewById(R.id.self_detail_title_bar);
        titleBarLayout.getRightIcon().setVisibility(View.GONE);
        titleBarLayout.setTitle("设置", ITitleBarLayout.Position.MIDDLE);
        titleBarLayout.setOnLeftClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        f_huanC.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CacheDataManager.clearAllCache(MySetActivity.this);
                try {
                    getTotalCacheSize();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        findViewById(R.id.tvXieyi1).setOnClickListener(v -> {
            Intent intent = new Intent(this, X5WebViewActivity.class);
            intent.putExtra("title", "用户协议");
            intent.putExtra("url", DemoApplication.TERMS_URL);
            startActivity(intent);

        });

        findViewById(R.id.tvXieyi2).setOnClickListener(v -> {
            Intent intent = new Intent(this, X5WebViewActivity.class);
            intent.putExtra("title", "隐私协议");
            intent.putExtra("url", DemoApplication.PRIVACY_URL);
            startActivity(intent);
        });
        findViewById(R.id.app).setOnClickListener(v -> PermissionUtils.launchAppDetailsSettings());
        findViewById(R.id.account_cancel).setOnClickListener(v -> account());

        findViewById(R.id.changePassword).setOnClickListener(v -> {
            Intent intent = new Intent(this, ChangePasswordActivity.class);
            intent.putExtra("type", 1);
            startActivity(intent);
        });
    }

    private void getTotalCacheSize() throws Exception {
        String cacheSize = CacheDataManager.getTotalCacheSize(this);
        t_huanC.setText(cacheSize);
    }


    private void getUserInfo() {
        String url = DemoApplication.NewAddress+"/api/user/get_user_info";
        OkGo.<String>get(url).tag(this).params("login_token", UserInfo.getInstance().getUserToken()).cacheMode(CacheMode.NO_CACHE).execute(new StringCallback() {
            @Override
            public void onSuccess(Response<String> response) {
                Log.i("get", response.body());
                try {
                    JSONObject object = new JSONObject(response.body());
                    if (object.has("code") && object.getInt("code") == 200) {
                        JSONObject data = object.getJSONObject("data");
                        TextView t_p = findViewById(R.id.t_p);
                        t_p.setText(data.getString("user_name"));
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
            }
        });
    }


    private void account() {
        if (cancelInFlight) {
            return;
        }
        TextView accountView = findViewById(R.id.t_p);
        String accountName = accountView == null ? "" : accountView.getText().toString();
        String message = "注销后当前账号将无法继续登录，相关数据不可恢复。\n当前账号：" + accountName;
        new TUIKitDialog(MySetActivity.this)
                .builder()
                .setCancelable(true)
                .setCancelOutside(true)
                .setTitle(message)
                .setDialogWidth(0.8f)
                .setPositiveButton("确认注销", v -> requestCancelAccount())
                .setNegativeButton("取消", v -> {})
                .show();
    }

    private void requestCancelAccount() {
        String loginToken = UserInfo.getInstance().getUserToken();
        if (loginToken == null || loginToken.trim().isEmpty()) {
            Toast.makeText(this, "登录状态已失效，请重新登录", Toast.LENGTH_SHORT).show();
            return;
        }
        cancelInFlight = true;
        String url = DemoApplication.NewAddress+"/api/user/cancel_account";
        OkGo.<String>post(url).tag(this).params("login_token", loginToken).cacheMode(CacheMode.NO_CACHE).execute(new StringCallback() {
            @Override
            public void onSuccess(Response<String> response) {
                try {
                    JSONObject object = new JSONObject(response.body());
                    if (object.has("code") && object.getInt("code") == 200) {
                        TUILogin.logout(new TUICallback() {
                            @Override
                            public void onSuccess() {
                                ProfileUtil.onLogoutSuccess(MySetActivity.this);
                            }

                            @Override
                            public void onError(int code, String desc) {
                                ProfileUtil.onLogoutSuccess(MySetActivity.this);
                            }
                        });
                        return;
                    }
                    cancelInFlight = false;
                    Toast.makeText(MySetActivity.this, object.optString("msg", "注销失败，请稍后重试"), Toast.LENGTH_SHORT).show();
                } catch (JSONException e) {
                    cancelInFlight = false;
                    Toast.makeText(MySetActivity.this, "注销响应异常，请稍后重试", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                cancelInFlight = false;
                Toast.makeText(MySetActivity.this, "网络异常，账号未注销", Toast.LENGTH_SHORT).show();
            }
        });
    }


}
