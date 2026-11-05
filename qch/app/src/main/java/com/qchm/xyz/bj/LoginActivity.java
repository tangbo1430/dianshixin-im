package com.qchm.xyz.bj;

import static com.qchm.xyz.TIMAppService.TAG;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.TIMAppService;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.view.X5WebViewActivity;
import com.qchm.xyz.config.AppConfig;
import com.qchm.xyz.imaccess.ImLoginCoordinator;
import com.qchm.xyz.imaccess.ImSession;
import com.qchm.xyz.imaccess.ImSessionException;
import com.qchm.xyz.main.MainActivity;
import com.qchm.xyz.utils.DemoLog;
import com.qchm.xyz.utils.TUIUtils;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;

import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

public class LoginActivity extends BaseLightActivity {

    public static final String EXTRA_REGISTERED_ACCOUNT = "registered_account";

    CheckBox iv_check;
    EditText etPhone;
    EditText etPass;
    View tvYS;
    View tvYH;
    View llReg;
    TextView loginButton;
    TextView registerLink;
    private boolean loggingIn;
    String account_reg_type=""; // 1 手机号   2 账号密码

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bj_activity_login);
        llReg = findViewById(R.id.llReg);
        iv_check = findViewById(R.id.iv_check);
        etPhone = findViewById(R.id.etPhone);
        etPass = findViewById(R.id.etPass);
        String registeredAccount = getIntent().getStringExtra(EXTRA_REGISTERED_ACCOUNT);
        if (!TextUtils.isEmpty(registeredAccount)) {
            etPhone.setText(registeredAccount);
        }
        tvYS = findViewById(R.id.tvYS);
        tvYH = findViewById(R.id.tvYH);
        registerLink = findViewById(R.id.tvRegister);
        loginButton = findViewById(R.id.tvLogin);
        registerLink.setOnClickListener(v -> {
            if (loggingIn) {
                return;
            }
            if(account_reg_type.equals("1")){
                Intent intent = new Intent(LoginActivity.this, RegistActivity.class);
                startActivity(intent);
            }else {
                Intent intent = new Intent(LoginActivity.this, RegistActivity2.class);
                startActivity(intent);
            }

        });
        tvYS.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, X5WebViewActivity.class);
            intent.putExtra("title", "隐私协议");
            intent.putExtra("url", DemoApplication.PRIVACY_URL);
            startActivity(intent);
        });
        tvYH.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, X5WebViewActivity.class);
            intent.putExtra("title", "用户协议");
            intent.putExtra("url", DemoApplication.TERMS_URL);
            startActivity(intent);
        });
        loginButton.setOnClickListener(v -> {
            if (loggingIn) {
                return;
            }
            if (iv_check.isChecked()) {
                String phone = etPhone.getText().toString();
                String pass = etPass.getText().toString();
                if (TextUtils.isEmpty(phone)) {
                    Toast.makeText(LoginActivity.this, "请输入手机号...", Toast.LENGTH_SHORT).show();
                } else if (TextUtils.isEmpty(pass)) {
                    Toast.makeText(LoginActivity.this, "请输入密码...", Toast.LENGTH_SHORT).show();
                } else {
                    setLoggingIn(true);
                    httpByLogin(phone, pass);
                }
            } else {
                Toast.makeText(LoginActivity.this, "请先阅读并同意用户协议和隐私协议...", Toast.LENGTH_SHORT).show();
            }
        });

    }

    private void httpByLogin(String phone, String pass) {
//        String url = DemoApplication.NewAddress+"/api/login/user_login";
        String url = DemoApplication.NewAddress+"/api/login/user_login_account";
        OkGo.<String>post(url)
                .tag(this)
                .params("user_login", phone)
                .params("password", pass)
//                .params("last_login_device", "001")
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {

                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                JSONObject data = object.getJSONObject("data");
                                loginIM(data.getString("login_token"));
                            } else {
                                setLoggingIn(false);
                                if (object.has("msg")) {
                                    Toast.makeText(LoginActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
                                }
                            }
                        } catch (JSONException e) {
                            setLoggingIn(false);
                            Toast.makeText(LoginActivity.this, "登录响应异常", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        setLoggingIn(false);
                        Toast.makeText(LoginActivity.this, "登录请求失败，请检查网络", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void loginIM(String loginToken) {
        ImLoginCoordinator.getInstance().loginWithToken(this, loginToken, new ImLoginCoordinator.Callback() {
            @Override
            public void onError(ImSessionException error) {
                setLoggingIn(false);
                ToastUtil.toastLongMessage(getString(R.string.failed_login_tip) + ": " + error.getMessage());
                DemoLog.i(TAG, "imLogin errorCode = " + error.getCode());
            }

            @Override
            public void onSuccess(ImSession session) {
                UserInfo.getInstance().setDebugLogin(true);
                Intent intent;
                if (AppConfig.DEMO_UI_STYLE == 0) {
                    intent = new Intent(LoginActivity.this, MainActivity.class);
                } else {
                    intent = new Intent(LoginActivity.this, MainActivity.class);
                }
                startActivity(intent);

                TIMAppService.getInstance().registerPushManually();

                finish();
            }
        });
    }

    private void setLoggingIn(boolean loggingIn) {
        this.loggingIn = loggingIn;
        loginButton.setEnabled(!loggingIn);
        loginButton.setAlpha(loggingIn ? 0.6f : 1f);
        loginButton.setText(loggingIn ? "登录中..." : "登录");
        registerLink.setEnabled(!loggingIn);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getReg();
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
                                JSONObject data = object.getJSONObject("data");
                                account_reg_type=data.getString("account_reg_type");
                                if (data.getString("show_reg").equals("1")) {
                                    llReg.setVisibility(View.VISIBLE);
                                } else {
                                    llReg.setVisibility(View.INVISIBLE);
                                }
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

}
