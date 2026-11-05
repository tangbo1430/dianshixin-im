package com.qchm.xyz.profile;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.DatePicker;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.UriUtils;
import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.utils.DemoLog;
import com.luck.picture.lib.basic.PictureSelector;
import com.luck.picture.lib.config.SelectMimeType;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.interfaces.OnResultCallbackListener;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.tencent.imsdk.v2.V2TIMCallback;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMSDKListener;
import com.tencent.imsdk.v2.V2TIMUserFullInfo;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import com.tencent.qcloud.tuicore.util.ErrorMessageConverter;
import com.tencent.qcloud.tuicore.util.ToastUtil;
import com.tencent.qcloud.tuicore.TUIConfig;
import com.tencent.qcloud.tuicore.TUILogin;
import com.tencent.qcloud.tuikit.tuichat.presenter.ChatPresenter;
import com.tencent.qcloud.tuikit.timcommon.component.LineControllerView;
import com.tencent.qcloud.tuikit.timcommon.component.PopupInputCard;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.activities.ImageSelectActivity;
import com.tencent.qcloud.tuikit.timcommon.component.activities.SelectionActivity;
import com.tencent.qcloud.tuikit.timcommon.component.dialog.TUIKitDialog;
import com.tencent.qcloud.tuikit.timcommon.component.gatherimage.ShadeImageView;
import com.tencent.qcloud.tuikit.timcommon.component.impl.GlideEngine;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.util.FileUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class SelfDetailActivity extends BaseLightActivity implements View.OnClickListener {
    private static final String TAG = SelfDetailActivity.class.getSimpleName();

    private static final int CHOOSE_AVATAR_REQUEST_CODE = 1;
    private TitleBarLayout titleBarLayout;
    private ShadeImageView selfIcon;
    private View faceArea;
    private LineControllerView nickNameLv;
    private LineControllerView accountLv;
    private LineControllerView genderLv;
    private LineControllerView birthdayLv;
    private LineControllerView signatureLv;

    private String faceUrl;
    private String nickName;
    private int gender;
    private long birthday;
    private String signature;
    private boolean avatarUpdating;
    private V2TIMSDKListener v2TIMSDKListener = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_self_detail);

        titleBarLayout = findViewById(R.id.self_detail_title_bar);
        faceArea = findViewById(R.id.face_url_area);
        selfIcon = findViewById(R.id.self_icon);
        nickNameLv = findViewById(R.id.modify_nick_name_lv);
        accountLv = findViewById(R.id.modify_account_lv);
        genderLv = findViewById(R.id.modify_gender_lv);
        birthdayLv = findViewById(R.id.modify_birthday_lv);
        signatureLv = findViewById(R.id.modify_signature_lv);

        setupViews();
    }

    private void setupViews() {
        titleBarLayout.getRightIcon().setVisibility(View.GONE);
        titleBarLayout.setTitle(getString(R.string.demo_profile_detail), ITitleBarLayout.Position.MIDDLE);
        titleBarLayout.setOnLeftClickListener(this);

        int radius = getResources().getDimensionPixelSize(R.dimen.demo_profile_face_radius);
        selfIcon.setRadius(radius);
        selfIcon.setOnClickListener(this);
        faceArea.setOnClickListener(this);
        nickNameLv.setOnClickListener(this);
        accountLv.setOnClickListener(this);
        genderLv.setOnClickListener(this);
        birthdayLv.setOnClickListener(this);
        signatureLv.setOnClickListener(this);

        String selfUserID = V2TIMManager.getInstance().getLoginUser();
        List<String> selfIdList = new ArrayList<>();
        selfIdList.add(selfUserID);
        V2TIMManager.getInstance().getUsersInfo(selfIdList, new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
            @Override
            public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                setUserInfo(v2TIMUserFullInfos.get(0));
            }

            @Override
            public void onError(int code, String desc) {
            }
        });
        setUserInfoListener();
    }

    private void setUserInfo(V2TIMUserFullInfo info) {
        faceUrl = info.getFaceUrl();
        int radius = getResources().getDimensionPixelSize(R.dimen.demo_profile_face_radius);
        GlideEngine.loadUserIcon(selfIcon, faceUrl, radius);
        nickName = info.getNickName();
        nickNameLv.setContent(nickName);
        birthday = info.getBirthday();
        String birthdayStr = String.valueOf(info.getBirthday());
        if (TextUtils.isEmpty(birthdayStr) || birthdayStr.length() < 8) {
            birthdayStr = "19700101";
        }
        StringBuilder sb = new StringBuilder(birthdayStr);
        sb.insert(4, "-");
        sb.insert(7, "-");
        birthdayStr = sb.toString();
        birthdayLv.setContent(birthdayStr);
        accountLv.setContent(info.getUserID());

        signature = info.getSelfSignature();
        signatureLv.setContent(signature);

        gender = info.getGender();
        String genderStr = getString(R.string.demo_self_detail_gender_unknown);
        if (gender == 1) {
            genderStr = getString(R.string.demo_self_detail_gender_male);
        } else if (gender == 2) {
            genderStr = getString(R.string.demo_self_detail_gender_female);
        }
        genderLv.setContent(genderStr);
    }

    private void setUserInfoListener() {
        v2TIMSDKListener = new V2TIMSDKListener() {
            @Override
            public void onSelfInfoUpdated(V2TIMUserFullInfo info) {
                setUserInfo(info);
            }
        };
        V2TIMManager.getInstance().addIMSDKListener(v2TIMSDKListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        V2TIMManager.getInstance().removeIMSDKListener(v2TIMSDKListener);
    }

    @Override
    public void onClick(View v) {
        if (v == selfIcon || v == faceArea) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                new TUIKitDialog(this)
                        .builder()
                        .setCancelable(true)
                        .setCancelOutside(true)
                        .setTitle("上传头像需要获取文件和相机权限")
                        .setDialogWidth(0.75f)
                        .setPositiveButton("同意",
                                v2 -> ActivityCompat.requestPermissions(this,
                                        new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                        10001))
                        .setNegativeButton("拒绝",
                                v1 -> {
                                })
                        .show();
            } else {
                PictureSelector.create(this)
                        .openGallery(SelectMimeType.ofImage())
                        .setImageEngine(com.tencent.qcloud.tuikit.tuichat.classicui.widget.input.GlideEngine.createGlideEngine())
                        .setMaxSelectNum(1)
                        .forResult(new OnResultCallbackListener<LocalMedia>() {
                            @Override
                            public void onResult(ArrayList<LocalMedia> result) {
                                if (result != null && !result.isEmpty()) {
                                    uploadAvatar(result.get(0).getAvailablePath());
                                }
                            }

                            @Override
                            public void onCancel() {

                            }
                        });
            }

        } else if (v == nickNameLv) {
            PopupInputCard popupInputCard = new PopupInputCard(this);
            popupInputCard.setContent(nickName);
            popupInputCard.setTitle(getString(R.string.demo_self_detail_modify_nickname));
            String description = getString(R.string.demo_self_detail_modify_nickname_rule);
            popupInputCard.setDescription(description);
            popupInputCard.setOnPositive((result -> {
            //判断 仅限中文、字母、数字和下划线
                if (!result.matches("^[a-zA-Z0-9_\u4e00-\u9fa5]+$")) {
                    Toast.makeText(this, description, Toast.LENGTH_LONG).show();
                    return;
                }
                nickName = result;
                updateProfile();
            }));
            View rootView = findViewById(android.R.id.content);
            popupInputCard.show(rootView, Gravity.BOTTOM);
        } else if (v == accountLv) {
            // do nothing
        } else if (v == genderLv) {
            setGender();
        } else if (v == birthdayLv) {
            setBirthday();
        } else if (v == signatureLv) {
            PopupInputCard popupInputCard = new PopupInputCard(this);
            popupInputCard.setContent(signature);
            popupInputCard.setTitle(getString(R.string.demo_self_detail_modify_signature));
            String description = getString(R.string.demo_self_detail_modify_nickname_rule);
            popupInputCard.setDescription(description);
            popupInputCard.setOnPositive((result -> {
                signature = result;
                updateProfile();
            }));
            View rootView = findViewById(android.R.id.content);
            popupInputCard.show(rootView, Gravity.BOTTOM);
        } else if (v == titleBarLayout.getLeftGroup()) {
            finish();
        }
    }

    private void setGender() {
        Bundle bundle = new Bundle();
        ArrayList<String> genderList = new ArrayList<>();
        genderList.add(getString(R.string.demo_self_detail_gender_male));
        genderList.add(getString(R.string.demo_self_detail_gender_female));
        bundle.putString(SelectionActivity.Selection.TITLE, getString(R.string.modify_gender));
        bundle.putStringArrayList(SelectionActivity.Selection.LIST, genderList);
        bundle.putInt(SelectionActivity.Selection.DEFAULT_SELECT_ITEM_INDEX, gender - 1);
        SelectionActivity.startListSelection(this, bundle, new SelectionActivity.OnResultReturnListener() {
            @Override
            public void onReturn(Object res) {
                gender = (Integer) res + 1;
                updateProfile();
            }
        });
    }

    public void setBirthday() {
        final Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        int month = c.get(Calendar.MONTH);
        int day = c.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dp = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker datePicker, int iyear, int monthOfYear, int dayOfMonth) {
                birthday = iyear * 10000 + (monthOfYear + 1) * 100 + dayOfMonth;
                updateProfile();
            }
        }, year, month, day);
        dp.show();
    }

    private void updateProfile() {
        updateProfile(faceUrl, false);
    }

    private void updateProfile(String targetFaceUrl, boolean avatarChange) {
        V2TIMUserFullInfo v2TIMUserFullInfo = new V2TIMUserFullInfo();
        if (!TextUtils.isEmpty(targetFaceUrl)) {
            v2TIMUserFullInfo.setFaceUrl(targetFaceUrl);
        }

        v2TIMUserFullInfo.setNickname(nickName);

        if (birthday != 0) {
            v2TIMUserFullInfo.setBirthday(birthday);
        }

        v2TIMUserFullInfo.setSelfSignature(signature);

        v2TIMUserFullInfo.setGender(gender);

        V2TIMManager.getInstance().setSelfInfo(v2TIMUserFullInfo, new V2TIMCallback() {
            @Override
            public void onError(int code, String desc) {
                String convertedDesc = ErrorMessageConverter.convertIMError(code, desc);
                DemoLog.e(TAG, "profile stage=tencent result=failed code=" + code + ", desc=" + convertedDesc);
                if (avatarChange) {
                    setAvatarUpdating(false);
                    ToastUtil.toastShortMessage("头像更新失败（腾讯 IM " + code + "）：" + convertedDesc);
                } else {
                    ToastUtil.toastShortMessage("资料更新失败（腾讯 IM " + code + "）：" + convertedDesc);
                }
            }

            @Override
            public void onSuccess() {
                DemoLog.i(TAG, "profile stage=tencent result=success avatarChange=" + avatarChange);
                if (!TextUtils.isEmpty(targetFaceUrl)) {
                    faceUrl = targetFaceUrl;
                    TUIConfig.setSelfFaceUrl(targetFaceUrl);
                    ChatPresenter.refreshUserFaceUrl(TUILogin.getLoginUser(), targetFaceUrl);
                    UserInfo.getInstance().setAvatar(targetFaceUrl);
                }
                UserInfo.getInstance().setName(nickName);
                if (avatarChange) {
                    int radius = getResources().getDimensionPixelSize(R.dimen.demo_profile_face_radius);
                    GlideEngine.loadUserIcon(selfIcon, targetFaceUrl, radius);
                    setAvatarUpdating(false);
                    ToastUtil.toastShortMessage("头像修改成功");
                }
                modify_user_info(nickName, targetFaceUrl, avatarChange);

            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CHOOSE_AVATAR_REQUEST_CODE && resultCode == ImageSelectActivity.RESULT_CODE_SUCCESS) {
            if (data != null) {
                ImageSelectActivity.ImageBean imageBean = (ImageSelectActivity.ImageBean) data.getSerializableExtra(ImageSelectActivity.DATA);
                if (imageBean == null) {
                    return;
                }

                faceUrl = imageBean.getImageUri();
                updateProfile();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                PictureSelector.create(this)
                        .openGallery(SelectMimeType.ofImage())
                        .setImageEngine(com.tencent.qcloud.tuikit.tuichat.classicui.widget.input.GlideEngine.createGlideEngine())
                        .setMaxSelectNum(1)
                        .forResult(new OnResultCallbackListener<LocalMedia>() {
                            @Override
                            public void onResult(ArrayList<LocalMedia> result) {
                                if (result.size() > 0) {

                                    uploadAvatar(result.get(0).getAvailablePath());
                                }
                            }

                            @Override
                            public void onCancel() {
                            }
                        });
            } else {
                ToastUtil.toastShortMessage("权限被拒绝，无法上传头像");
            }
        }
    }


    private void uploadAvatar(String pic) {
        if (avatarUpdating) {
            ToastUtil.toastShortMessage("头像正在上传，请稍候");
            return;
        }

        if (TextUtils.isEmpty(pic)) {
            failAvatarUpdate("select", "未读取到所选图片", null);
            return;
        }

        File head;
        try {
            head = pic.startsWith("content://") ? UriUtils.uri2File(Uri.parse(pic)) : new File(pic);
        } catch (RuntimeException e) {
            failAvatarUpdate("read", "无法读取所选图片", e);
            return;
        }
        if (head == null || !head.exists() || !head.isFile() || !head.canRead() || head.length() <= 0) {
            failAvatarUpdate("read", "所选图片不可读取，请重新选择", null);
            return;
        }

        setAvatarUpdating(true);
        DemoLog.i(TAG, "avatar stage=upload result=start size=" + head.length());
        ToastUtil.toastShortMessage("正在上传头像...");

        OkGo.<String>post(DemoApplication.NewAddress + "/api/upload/img_upload")
                .params("login_token", UserInfo.getInstance().getUserToken() + "")
                .params("file[]", head)
                .params("file_category", "avatar")
                .isMultipart(true)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String body = response.body();
                        DemoLog.i(TAG, "avatar stage=upload result=response http=" + response.code()
                                + ", bodyLength=" + (body == null ? 0 : body.length()));
                        try {
                            JSONObject object = new JSONObject(body == null ? "" : body);
                            int businessCode = object.optInt("code", -1);
                            String message = object.optString("msg", "上传服务返回异常");
                            JSONArray data = object.optJSONArray("data");
                            if (businessCode != 200 || data == null || data.length() == 0) {
                                String codeText = businessCode == 200 ? "" : "（" + businessCode + "）";
                                failAvatarUpdate("upload", "头像上传失败" + codeText + "：" + message, null);
                                return;
                            }

                            String uploadedUrl = data.optString(0, "").trim();
                            if (!uploadedUrl.startsWith("https://")) {
                                failAvatarUpdate("upload", "头像上传返回了无效地址", null);
                                return;
                            }
                            DemoLog.i(TAG, "avatar stage=upload result=success urlHost="
                                    + Uri.parse(uploadedUrl).getHost());
                            updateProfile(uploadedUrl, true);
                        } catch (JSONException e) {
                            failAvatarUpdate("parse", "头像上传响应格式错误", e);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        int httpCode = response == null ? -1 : response.code();
                        Throwable error = response == null ? null : response.getException();
                        failAvatarUpdate("network", "头像上传网络失败（HTTP " + httpCode + "）", error);
                    }
                });
    }

    private void modify_user_info(String name, String head, boolean avatarChange) {
        OkGo.<String>post(DemoApplication.NewAddress + "/api/user/modify_user_info")
                .params("login_token", UserInfo.getInstance().getUserToken() + "")
                .params("nick_name", name)
                .params("head_pic", head)
                .params("sex", gender)
                .cacheMode(CacheMode.NO_CACHE)
                .execute(new StringCallback() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject object = new JSONObject(response.body() == null ? "" : response.body());
                            int code = object.optInt("code", -1);
                            String message = object.optString("msg", "业务资料同步异常");
                            if (code == 200) {
                                DemoLog.i(TAG, "profile stage=business-sync result=success");
                            } else {
                                DemoLog.e(TAG, "profile stage=business-sync result=failed code=" + code
                                        + ", msg=" + message);
                                if (avatarChange) {
                                    ToastUtil.toastShortMessage("头像已更新，但业务资料同步失败（" + code + "）：" + message);
                                }
                            }
                        } catch (JSONException e) {
                            DemoLog.e(TAG, "profile stage=business-sync result=parse-failed error=" + safeError(e));
                            if (avatarChange) {
                                ToastUtil.toastShortMessage("头像已更新，但业务资料同步响应异常");
                            }
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        int httpCode = response == null ? -1 : response.code();
                        Throwable error = response == null ? null : response.getException();
                        DemoLog.e(TAG, "profile stage=business-sync result=network-failed http=" + httpCode
                                + ", error=" + safeError(error));
                        if (avatarChange) {
                            ToastUtil.toastShortMessage("头像已更新，但业务资料同步失败（HTTP " + httpCode + "）");
                        }
                    }
                });
    }

    private void setAvatarUpdating(boolean updating) {
        avatarUpdating = updating;
        selfIcon.setEnabled(!updating);
        faceArea.setEnabled(!updating);
    }

    private void failAvatarUpdate(String stage, String userMessage, Throwable error) {
        setAvatarUpdating(false);
        DemoLog.e(TAG, "avatar stage=" + stage + " result=failed message=" + userMessage
                + ", error=" + safeError(error));
        ToastUtil.toastShortMessage(userMessage);
    }

    private String safeError(Throwable error) {
        if (error == null) {
            return "none";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (TextUtils.isEmpty(message) ? "" : ": " + message);
    }

    public Uri getPhotoUri(String photoPath) {
        return FileUtil.getUriFromPath(photoPath);
    }
}
