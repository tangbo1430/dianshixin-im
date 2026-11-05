package com.qchm.xyz.profile;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.GsonUtils;
import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.bean.SignBean;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.view.X5WebViewActivity;
import com.qchm.xyz.utils.ProfileUtil;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuicore.interfaces.TUICallback;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuikit.timcommon.component.dialog.TUIKitDialog;

import org.json.JSONException;
import org.json.JSONObject;

public class ProfileFragment extends Fragment {
    private View mBaseView;
    private ProfileLayout mProfileLayout;
    String customer_id;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        mBaseView = inflater.inflate(R.layout.profile_fragment, container, false);
        initView();
        return mBaseView;
    }

    @Override
    public void onResume() {
        super.onResume();
        getUserInfo();
        if (mProfileLayout != null) {
            mProfileLayout.initUI();
        }
    }

    private void initView() {
        mProfileLayout = mBaseView.findViewById(R.id.profile_view);
        mBaseView.findViewById(R.id.logout_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TUIKitDialog(getActivity())
                        .builder()
                        .setCancelable(true)
                        .setCancelOutside(true)
                        .setTitle(getString(R.string.logout_tip))
                        .setDialogWidth(0.75f)
                        .setPositiveButton("确定",
                                new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        TUILogin.logout(new TUICallback() {
                                            @Override
                                            public void onSuccess() {
                                                ProfileUtil.onLogoutSuccess(getActivity());
                                            }

                                            @Override
                                            public void onError(int code, String desc) {
                                                ToastUtil.toastLongMessage("logout fail: " + code + "=" + desc);
                                                ProfileUtil.onLogoutSuccess(getActivity());
                                            }
                                        });
                                    }
                                })
                        .setNegativeButton("取消",
                                new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                    }
                                })
                        .show();
            }
        });
        mBaseView.
                findViewById(R.id.tv6).setOnClickListener(v -> {
//                    if (TextUtils.isEmpty(customer_id)) return;
//                    ConversationInfo conversationInfo = new ConversationInfo();
//                    conversationInfo.setGroup(false);
//                    conversationInfo.setId(customer_id);
//                    TUIConversationUtils.startChatActivity(conversationInfo);.
                    Intent intent = new Intent(requireActivity(), X5WebViewActivity.class);
                    intent.putExtra("title", "平台客服");
                    intent.putExtra("url", DemoApplication.NewAddress+"/index/index/page_show?id=6");
                    startActivity(intent);
                });
        mBaseView.findViewById(R.id.tvToday).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSignList();

            }
        });


        mBaseView.findViewById(R.id.imQRCode).setOnClickListener(v -> {
            ActivityUtils.startActivity(MyScanActivity.class);
        });

    }


    private void getSignList() {
        String url = DemoApplication.NewAddress+"/api/sign_config/get_sign_config";
        OkGo.<String>get(url)
                .tag(this)
                .params("login_token", UserInfo.getInstance().getUserToken() + "")
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            SignBean signBean = GsonUtils.fromJson(response.body(), SignBean.class);
                            if (signBean.getCode() == 200) {
                                Log.e("===HTTP===", GsonUtils.toJson(signBean));
                                new DailyCheckInDialog(getContext(), signBean.getData()).show();
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                    }
                });
    }

    public void reloadData() {
        if (mProfileLayout != null) {
            mProfileLayout.loadUserInfo();
        }
    }


    private void getUserInfo() {
        String url = DemoApplication.NewAddress+"/api/user/get_user_info";
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
                                customer_id = data.getString("customer_id");
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
