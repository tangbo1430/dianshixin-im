package com.qchm.xyz.profile;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import com.blankj.utilcode.util.ImageUtils;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.qchm.xyz.R;

import com.bumptech.glide.Glide;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMUserFullInfo;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import com.qchm.xyz.bean.UserInfo;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyScanActivity extends BaseLightActivity {
    private TitleBarLayout titleBarLayout;
    private ImageView ivScan;
    private ImageView rivView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_self_scan);
        titleBarLayout = findViewById(R.id.self_detail_title_bar);
        ivScan = findViewById(R.id.ivScan);
        rivView = findViewById(R.id.rivView);
        titleBarLayout.getRightIcon().setVisibility(View.GONE);
        titleBarLayout.setTitle("二维码", ITitleBarLayout.Position.MIDDLE);
        titleBarLayout.setOnLeftClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        Bitmap mBitmap = generateQRCode(UserInfo.getInstance().getUserId(), 400, 400);
        ivScan.setImageBitmap(mBitmap);
        List<String> list = new ArrayList<>();
        list.add(UserInfo.getInstance().getUserId());
        V2TIMManager.getInstance().getUsersInfo(list, new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
            @Override
            public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                try {
                    Glide.with(getApplicationContext()).load(v2TIMUserFullInfos.get(0).getFaceUrl()).error(R.mipmap.ic_launcher).into(rivView);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onError(int i, String s) {
            }
        });
    }
    private Bitmap generateQRCode(String text, int width, int height) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1); // 设置白边

            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    width,
                    height,
                    hints
            );

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
