package com.qchm.xyz.profile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.qchm.xyz.DemoApplication;
import com.qchm.xyz.R;
import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.callback.StringCallback;
import com.lzy.okgo.model.Response;
import com.qchm.xyz.bean.UserInfo;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;

import org.json.JSONException;
import org.json.JSONObject;

public class InviteFriendsActivity extends BaseLightActivity {
    String imageUrl;
    ImageView imQRCode;
    TextView tvRegCode;
    String code;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invite_friends);
        TitleBarLayout title_bar = findViewById(R.id.title_bar);
        title_bar.setTitle("邀请好友", ITitleBarLayout.Position.MIDDLE);
        imQRCode = findViewById(R.id.imQRCode);
        tvRegCode = findViewById(R.id.tvRegCode);
        getUserInfo();
        findViewById(R.id.imInvite).setOnClickListener(v -> {
            //跳转系统浏览器
            try {
                startActivity(new Intent("android.intent.action.VIEW", android.net.Uri.parse(imageUrl)));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        findViewById(R.id.imCopy).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setText(code);
            Toast.makeText(InviteFriendsActivity.this, "复制成功", Toast.LENGTH_LONG).show();
        });
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
                                code = data.getString("reg_code");
                                tvRegCode.setText("我的邀请码：" + code);
                                imageUrl = data.getString("invite_url");
                                generateQRCode(imageUrl);
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


    private void generateQRCode(String content) {
        QRCodeWriter writer = new QRCodeWriter();
        try {
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            imQRCode.setImageBitmap(bmp); // 显示二维码
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }
}
