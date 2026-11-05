package com.qchm.xyz.bj;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.blankj.utilcode.util.GsonUtils;
import com.bumptech.glide.Glide;
import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.TIMAppService;
import com.qchm.xyz.bean.CaptchaBean;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.view.X5WebViewActivity;
import com.qchm.xyz.config.AppConfig;
import com.qchm.xyz.imaccess.ImLoginCoordinator;
import com.qchm.xyz.imaccess.ImSession;
import com.qchm.xyz.imaccess.ImSessionException;
import com.qchm.xyz.main.MainActivity;
import com.qchm.xyz.utils.DemoLog;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;

import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

public class RegistActivity2 extends BaseLightActivity {

    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{7,21}$");


    CheckBox iv_check;
    EditText etPass;
    EditText etPass1;
    EditText etPhone;
    EditText etCode;
    EditText etPass2;
    View tvYS;
    View tvYH;
    ImageView imCode;
    TextView registerButton;
    TextView loginLink;

    private CaptchaBean captchaBean;
    private boolean registering;
    private boolean accountRegistered;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("account_registered", accountRegistered);
        super.onSaveInstanceState(outState);
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bj_activity_regist2);
        imCode = findViewById(R.id.imCode);
        iv_check = findViewById(R.id.iv_check);
        etPhone = findViewById(R.id.etPhone);
        etPass = findViewById(R.id.etPass);
        etPass1 = findViewById(R.id.etPass1);
        etPass2 = findViewById(R.id.etPass2);
        tvYS = findViewById(R.id.tvYS);
        tvYH = findViewById(R.id.tvYH);
        etCode = findViewById(R.id.etCode);
        loginLink = findViewById(R.id.tvRegister);
        registerButton = findViewById(R.id.tvLogin);
        etPhone.setHint("8–22 位，字母开头，可用字母、数字和下划线");
        etPass.setHint("请输入 6–16 位密码");
        etPass1.setHint("请再次输入密码");
        loginLink.setOnClickListener(v -> {
            if (registering) {
                return;
            }
            Intent intent = new Intent(RegistActivity2.this, LoginActivity.class);
            startActivity(intent);
        });
        tvYS.setOnClickListener(v -> {
            Intent intent = new Intent(RegistActivity2.this, X5WebViewActivity.class);
            intent.putExtra("title", "隐私协议");
            intent.putExtra("url", DemoApplication.PRIVACY_URL);
            startActivity(intent);
        });
        tvYH.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(RegistActivity2.this, X5WebViewActivity.class);
                intent.putExtra("title", "用户协议");
                intent.putExtra("url", DemoApplication.TERMS_URL);
                startActivity(intent);
            }
        });
        registerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (registering) {
                    showRegistrationError("注册正在提交，请稍候");
                    return;
                }
                if (accountRegistered) {
                    setRegistering(true);
                    httpByLogin(etPhone.getText().toString().trim(), etPass.getText().toString());
                    return;
                }
                if (iv_check.isChecked()) {
                    String phone = etPhone.getText().toString().trim();
                    String pass = etPass.getText().toString();
                    String code = etCode.getText().toString().trim();
                    String pass2 = etPass1.getText().toString();
                    String pass3 = etPass2.getText().toString().trim();
                    int passwordLength = pass.codePointCount(0, pass.length());
                    if (TextUtils.isEmpty(phone)) {
                        showRegistrationError("请输入账号");
                    } else if (!ACCOUNT_PATTERN.matcher(phone).matches()) {
                        showRegistrationError("账号须为 8–22 位，以字母开头，可使用字母、数字和下划线");
                    } else if (TextUtils.isEmpty(pass)) {
                        showRegistrationError("请输入密码");
                    } else if (passwordLength < 6 || passwordLength > 16) {
                        showRegistrationError("密码须为 6–16 位");
                    } else if (!pass.equals(pass2)) {
                        showRegistrationError("两次输入的密码不一致");
                    } else if (TextUtils.isEmpty(code)) {
                        showRegistrationError("请输入图形验证码");
                    } else if (captchaBean == null || TextUtils.isEmpty(captchaBean.getKey())) {
                        showRegistrationError("验证码暂未加载，请点击图片重新获取");
                    } else {
                        setRegistering(true);
                        httpByRegister(phone, pass, code, pass2,pass3);
                    }
                } else {
                    showRegistrationError("请先阅读并同意用户协议和隐私政策");
                }
            }
        });
        imCode.setOnClickListener(v -> getCode());
        accountRegistered = savedInstanceState != null
                && savedInstanceState.getBoolean("account_registered", false);
        setRegistering(false);
        if (!accountRegistered) getCode();
    }

    private void getCode() {
        String getCodeUrl = DemoApplication.NewAddress + "/api/login/create_captcha";
        OkGo.<String>post(getCodeUrl)
                .tag(this)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                captchaBean = GsonUtils.fromJson(object.getString("data"), CaptchaBean.class);
                                Glide.with(RegistActivity2.this).load(captchaBean.getImage()).into(imCode);
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

    private void httpByRegister(String phone, String pass, String code, String pass2,String pass3) {
//        String url = DemoApplication.NewAddress + "/api/login/user_reg";
        String url = DemoApplication.NewAddress + "/api/login/user_reg_account";
        OkGo.<String>post(url)
                .tag(this)
                .params("user_login", phone)
                .params("password", pass)
                .params("system_type", 2)
//                .params("sms_code", code)
//                .params("reg_code", pass2)
                .params("repassword", pass2)
                .params("captcha_code", code)
                .params("captcha_key", captchaBean.getKey())
                .params("invite_code",  pass3)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {

                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                accountRegistered = true;
                                setRegistering(true);
                                httpByLogin(phone, pass);
                            } else {
                                setRegistering(false);
                                showRegistrationError(registrationErrorMessage(object.optString("msg")));
                            }
                        } catch (JSONException e) {
                            setRegistering(false);
                            showRegistrationError(registrationFallbackMessage());
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        setRegistering(false);
                        showRegistrationError("网络连接失败，请检查网络后重试；账号须为 8–22 位，密码须为 6–16 位");
                    }
                });
    }

    private void httpByLogin(String phone, String pass) {
        String url = DemoApplication.NewAddress + "/api/login/user_login_account";
        OkGo.<String>post(url)
                .tag(this)
                .params("user_login", phone)
                .params("password", pass)
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
                                registrationLoginFailed("账号登录未完成，请点击重试");
                            }
                        } catch (JSONException e) {
                            registrationLoginFailed("登录响应异常，请点击重试");
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        registrationLoginFailed("网络连接失败，请点击重试");
                    }
                });
    }

    private void loginIM(String loginToken) {
        ImLoginCoordinator.getInstance().loginWithToken(this, loginToken, new ImLoginCoordinator.Callback() {
            @Override
            public void onError(ImSessionException error) {
                DemoLog.i(TIMAppService.TAG, "imLogin errorCode = " + error.getCode());
                registrationLoginFailed("聊天连接未完成，请点击重试");
            }

            @Override
            public void onSuccess(ImSession session) {
                UserInfo.getInstance().setDebugLogin(true);
                Intent intent;
                if (AppConfig.DEMO_UI_STYLE == 0) {
                    intent = new Intent(RegistActivity2.this, MainActivity.class);
                } else {
                    intent = new Intent(RegistActivity2.this, MainActivity.class);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                TIMAppService.getInstance().registerPushManually();
                finish();
            }
        });
    }

    private void setRegistering(boolean registering) {
        this.registering = registering;
        registerButton.setEnabled(!registering);
        registerButton.setAlpha(registering ? 0.6f : 1f);
        registerButton.setText(accountRegistered
                ? (registering ? "登录中..." : "重试进入")
                : (registering ? "注册中..." : "注册"));
        etPhone.setEnabled(!accountRegistered && !registering);
        etPass.setEnabled(!accountRegistered && !registering);
        etPass1.setEnabled(!accountRegistered && !registering);
        etPass2.setEnabled(!accountRegistered && !registering);
        etCode.setEnabled(!accountRegistered && !registering);
        loginLink.setEnabled(!registering);
        imCode.setEnabled(!registering);
    }

    private void showRegistrationError(String message) {
        Toast.makeText(RegistActivity2.this, message, Toast.LENGTH_LONG).show();
    }

    private String registrationErrorMessage(String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.trim().toLowerCase(Locale.ROOT);
        if (containsAny(message, "验证码", "captcha", "verification code")) {
            return "图形验证码错误或已过期，请重新获取后再试";
        }
        if (containsAny(message, "邀请码", "invite code", "invite_code")) {
            return "邀请码无效，请检查后重试，或清空邀请码后继续";
        }
        if (containsAny(message, "密码", "password", "repassword")) {
            return "密码须为 6–16 位，且两次输入一致";
        }
        if (containsAny(message, "账号", "用户名", "username", "account")) {
            if (containsAny(message, "已存在", "已注册", "重复", "占用", "被使用", "already", "duplicate", "taken")) {
                return "该账号已注册，请直接登录";
            }
            return "账号须为 8–22 位，以字母开头，可使用字母、数字和下划线";
        }
        return registrationFallbackMessage();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private String registrationFallbackMessage() {
        return "注册未完成，请检查账号（8–22 位，字母开头）和密码（6–16 位）后重试";
    }

    private void registrationLoginFailed(String message) {
        setRegistering(false);
        showRegistrationError("注册成功，" + message);
    }

}
