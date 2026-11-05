package com.qchm.xyz.profile;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.qchm.xyz.DemoApplication;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.qchm.xyz.R;
import com.qchm.xyz.bean.SignDataBean;
import com.qchm.xyz.bean.UserInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class DailyCheckInDialog extends Dialog {
    ArrayList<SignDataBean> dataList;
    ArrayList<View> bgList = new ArrayList<>();
    ArrayList<TextView> tvDayList = new ArrayList<>();
    String   dayNum;
    private TextView tvSubmit;
    private boolean signInSubmitting;

    public DailyCheckInDialog(@NonNull Context context, ArrayList<SignDataBean> data) {
        super(context, R.style.myChooseDialog);
        this.dataList = data;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_check_in);
        setWidows();
        initView();
        for (int i = 0; i < dataList.size(); i++) {
            //is_today 是否今天  1 是 2 否
            //sign_status 1 已签到  2 未签到  3 不可签到
            if (dataList.get(i).is_today() == 1) {
                dayNum = dataList.get(i).getDay_num() + "";
                bgList.get(i).setBackgroundResource(R.mipmap.icon_check_in_status2);
                tvDayList.get(i).setTextColor(Color.WHITE);

                if (dataList.get(i).getSign_status() == 1) {
                    tvSubmit.setText("已签到");
                    tvSubmit.setEnabled(false);
                }
            } else {
                if (dataList.get(i).getSign_status() == 3) {
                    bgList.get(i).setBackgroundResource(R.mipmap.icon_check_in_status1);
                    tvDayList.get(i).setTextColor(Color.parseColor("#999999"));
                } else {
                    bgList.get(i).setBackgroundResource(R.mipmap.icon_check_in_status3);
                    tvDayList.get(i).setTextColor(Color.parseColor("#999999"));
                }

            }
            if (dataList.get(i).getSign_status() == 1) {
                if (dataList.get(i).getDay_time_format() > 0) {
                    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd");
                    String formattedDate = sdf.format(new Date(dataList.get(i).getDay_time_format() * 1000));
                    tvDayList.get(i).setText(formattedDate);
                }
            }

        }
    }

    private void initView() {
        bgList.add(findViewById(R.id.bg1));
        bgList.add(findViewById(R.id.bg2));
        bgList.add(findViewById(R.id.bg3));
        bgList.add(findViewById(R.id.bg4));
        bgList.add(findViewById(R.id.bg5));
        bgList.add(findViewById(R.id.bg6));
        bgList.add(findViewById(R.id.bg7));
        tvDayList.add(findViewById(R.id.tvDay1));
        tvDayList.add(findViewById(R.id.tvDay2));
        tvDayList.add(findViewById(R.id.tvDay3));
        tvDayList.add(findViewById(R.id.tvDay4));
        tvDayList.add(findViewById(R.id.tvDay5));
        tvDayList.add(findViewById(R.id.tvDay6));
        tvDayList.add(findViewById(R.id.tvDay7));
        tvSubmit = findViewById(R.id.btn_submit);
        tvSubmit.setOnClickListener(v -> {
            postSign();
        });
    }

    private void setWidows() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.alpha = 1.0f;
        lp.gravity = Gravity.CENTER;
        getWindow().setAttributes(lp);
        this.setCanceledOnTouchOutside(true);
    }


    private void postSign() {
        if (signInSubmitting || dayNum == null) {
            return;
        }
        String url = DemoApplication.NewAddress+"/api/sign_config/sign_in";
        signInSubmitting = true;
        tvSubmit.setEnabled(false);
        OkGo.<String>post(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken() + "")
                .params("day_num", dayNum)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject result = new JSONObject(response.body());
                            if (result.optInt("code") == 200) {
                                new CheckInDialog(getContext(), dayNum).show();
                                dismiss();
                                return;
                            }
                            Toast.makeText(getContext(), result.optString("msg", "签到失败"), Toast.LENGTH_LONG).show();
                        } catch (JSONException e) {
                            Toast.makeText(getContext(), "签到响应异常", Toast.LENGTH_LONG).show();
                        }
                        signInSubmitting = false;
                        tvSubmit.setEnabled(true);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        signInSubmitting = false;
                        tvSubmit.setEnabled(true);
                        Toast.makeText(getContext(), "签到失败，请稍后重试", Toast.LENGTH_LONG).show();
                    }
                });
    }


}
