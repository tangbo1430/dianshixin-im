package com.qchm.xyz.profile;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.blankj.utilcode.util.GsonUtils;
import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.bean.CaptchaBean;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.view.GraphicCodeDialog;
import com.qchm.xyz.bj.view.X5WebViewActivity;
import com.qchm.xyz.utils.ProfileUtil;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class ChangePasswordActivity extends BaseLightActivity {
    TextView tvCode;
    EditText etPass1;
    EditText etPass2;
    EditText etPhone;
    EditText etCode;
    CountDownTimer timer;
    private GraphicCodeDialog dialog;
    private CaptchaBean captchaBean;
    private TitleBarLayout title_bar;
    private TextView submitButton;
    private boolean submitting;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_pass);
        title_bar = findViewById(R.id.title_bar);
        tvCode = findViewById(R.id.tvCode);
        etPhone = findViewById(R.id.etPhone);
        etPass1 = findViewById(R.id.etPass1);
        etPass2 = findViewById(R.id.etPass2);
        etCode = findViewById(R.id.etCode);
        submitButton = findViewById(R.id.tvLogin);
        title_bar.setTitle("修改密码", ITitleBarLayout.Position.MIDDLE);

        submitButton.setOnClickListener(v -> {
            if (submitting) {
                return;
            }
            String pass1 = etPass1.getText().toString();
            String pass2 = etPass2.getText().toString();
            if (TextUtils.isEmpty(pass1) || TextUtils.isEmpty(pass2)) {
                Toast.makeText(ChangePasswordActivity.this, "请输入密码...", Toast.LENGTH_SHORT).show();
            } else {
                setSubmitting(true);
                httpByRegister3(pass1, pass2);
//                if (getIntent().getIntExtra("type", 1) == 1) {
//                    httpByRegister(phone, pass, code);
//                } else {
//                    httpByRegister2(pass, code);
//                }
            }
        });
        getUrl();
        getUserInfo();

        tvCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String phone = etPhone.getText().toString();
                if (TextUtils.isEmpty(phone)) {
                    Toast.makeText(ChangePasswordActivity.this, "请输入手机号...", Toast.LENGTH_SHORT).show();
                } else {
                    getCode(phone);
                }
            }
        });
    }


    private void httpByRegister(String phone, String pass, String code) {
        String url = DemoApplication.NewAddress + "/api/user/modify_password";
        OkGo.<String>post(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken())
                .params("mobile", phone)
                .params("password", pass)
                .params("sms_code", code)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                TUILogin.logout(new TUICallback() {
                                    @Override
                                    public void onSuccess() {
                                        Toast.makeText(ChangePasswordActivity.this, "修改成功", Toast.LENGTH_SHORT).show();
                                        ProfileUtil.onLogoutSuccess(ChangePasswordActivity.this);
                                    }

                                    @Override
                                    public void onError(int code, String desc) {
                                        ToastUtil.toastLongMessage("logout fail: " + code + "=" + desc);
                                        ProfileUtil.onLogoutSuccess(ChangePasswordActivity.this);
                                    }
                                });
                            }
                            Toast.makeText(ChangePasswordActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
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

    private void httpByRegister3(String pass, String pass2) {
        String url = DemoApplication.NewAddress + "/api/user/modify_password_account";
        OkGo.<String>post(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken())
                .params("old_password", pass)
                .params("password", pass2)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {

                                TUILogin.logout(new TUICallback() {
                                    @Override
                                    public void onSuccess() {
                                        Toast.makeText(ChangePasswordActivity.this, "修改成功", Toast.LENGTH_SHORT).show();
                                        ProfileUtil.onLogoutSuccess(ChangePasswordActivity.this);
                                    }

                                    @Override
                                    public void onError(int code, String desc) {
                                        Toast.makeText(ChangePasswordActivity.this, "修改成功，请重新登录", Toast.LENGTH_SHORT).show();
                                        ProfileUtil.onLogoutSuccess(ChangePasswordActivity.this);
                                    }
                                });
                            } else {
                                setSubmitting(false);
                            }
                            Toast.makeText(ChangePasswordActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
                        } catch (JSONException e) {
                            setSubmitting(false);
                            Toast.makeText(ChangePasswordActivity.this, "修改密码响应异常", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        setSubmitting(false);
                        Toast.makeText(ChangePasswordActivity.this, "修改密码失败，请检查网络", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setSubmitting(boolean submitting) {
        this.submitting = submitting;
        submitButton.setEnabled(!submitting);
        submitButton.setAlpha(submitting ? 0.6f : 1f);
        submitButton.setText(submitting ? "修改中..." : "提交");
    }

    private void httpByRegister2(String pass, String code) {
        String url = DemoApplication.NewAddress + "/api/user/modify_trade_password";
        OkGo.<String>post(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken())
                .params("trade_password", pass)
                .params("sms_code", code)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            Toast.makeText(ChangePasswordActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
                            if (object.has("code") && object.getInt("code") == 200) {
                                finish();
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


    private void getCode(String phone) {
        String getCodeUrl = DemoApplication.NewAddress + "/api/login/create_captcha";

        OkGo.<String>post(getCodeUrl)
                .tag(this)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                captchaBean = GsonUtils.fromJson(object.getString("data"), CaptchaBean.class);
                                if (dialog == null || !dialog.isShowing()) {
                                    dialog = new GraphicCodeDialog(ChangePasswordActivity.this);
                                    dialog.setOnGraphicCodeListener(code -> {
                                        verification(phone, code);
                                        dialog.dismiss();
                                    });
                                }
                                dialog.show();
                                dialog.setImage(captchaBean.getImage());
                                dialog.setImageClick(v -> getCode(phone));
                            } else {
                                Toast.makeText(ChangePasswordActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
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

    private void verification(String phone, String code) {
        String url = DemoApplication.NewAddress + "/api/login/send_sms";
        OkGo.<String>post(url)
                .tag(this)
                .params("mobile", phone)
                .params("type", "2")
                .params("captcha_code", code)
                .params("captcha_key", captchaBean.getKey())
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                showCode();
                            }
                            Toast.makeText(ChangePasswordActivity.this, object.getString("msg"), Toast.LENGTH_SHORT).show();
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

    private void getUserInfo() {
        String url = DemoApplication.NewAddress + "/api/user/get_user_info";
        OkGo.<String>get(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken())
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        Log.i("get", response.body());
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                JSONObject data = object.getJSONObject("data");
                                etPhone.setText(
                                        data.getString("user_name"));
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


    private void getUrl() {
        String url = DemoApplication.NewAddress + "/api/login/show_reg";
        OkGo.<String>post(url)
                .tag(this)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body());
                            if (object.has("code") && object.getInt("code") == 200) {
                                object = object.getJSONObject("data");
                                String url = object.getString("customer_link");
                                //object.getString("customer_open_status")
                                if ("1".equals(object.getString("customer_open_status"))) {
                                    title_bar.setTitle("在线客服", ITitleBarLayout.Position.RIGHT);
                                }
                                title_bar.getRightGroup().setOnClickListener(v -> {
                                    Intent intent = new Intent(ChangePasswordActivity.this, X5WebViewActivity.class);
                                    intent.putExtra("title", "在线客服");
                                    intent.putExtra("url", url);
                                    startActivity(intent);
                                });

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

    @Override
    protected void onStop() {
        super.onStop();
        if (timer != null)
            timer.cancel();
    }


    private void showCode() {
        tvCode.setEnabled(false);
        timer = new CountDownTimer(60 * 1000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int i = Integer.parseInt((millisUntilFinished / 1000) + "");
                tvCode.setText(i + "");
            }

            @Override
            public void onFinish() {
                tvCode.setText("重新发送");
                tvCode.setEnabled(true);
            }
        }.start();
    }


}
