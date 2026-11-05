package com.qchm.xyz.main;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.qchm.xyz.R;
import com.luck.picture.lib.basic.PictureSelector;
import com.luck.picture.lib.config.SelectMimeType;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.interfaces.OnResultCallbackListener;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMUserFullInfo;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import com.tencent.qcloud.tuicore.TUIConstants;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.util.FileUtil;
import com.tencent.qcloud.tuikit.tuichat.classicui.page.FriendProfileActivity;
import com.tencent.qcloud.tuikit.tuichat.classicui.widget.input.GlideEngine;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ScanActivity extends BaseLightActivity {
    DecoratedBarcodeView barcodeView;

    public Uri getPhotoUri(String photoPath) {
        return FileUtil.getUriFromPath(photoPath);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(R.layout.activity_scan);
        barcodeView = findViewById(R.id.barcode_scanner);
        //隐藏提示语
        barcodeView.getStatusView().setVisibility(View.GONE);
        barcodeView.decodeSingle(result -> getResult(result.getText()));
        findViewById(R.id.v_back).setOnClickListener(v -> finish());
        findViewById(R.id.tvXC).setOnClickListener(v -> PictureSelector.create(ScanActivity.this)
                .openGallery(SelectMimeType.ofImage())
                .setImageEngine(GlideEngine.createGlideEngine())
                .setMaxSelectNum(1)
                .forResult(new OnResultCallbackListener<LocalMedia>() {
                    @Override
                    public void onResult(ArrayList<LocalMedia> result) {
                        Uri uri = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            uri = Uri.parse(result.get(0).getPath());
                        } else {
                            uri = getPhotoUri(result.get(0).getPath());
                        }
                        Bitmap photoBmp = null;
                        if (uri != null) {
                            try {
                                photoBmp = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                               getResult( decodeQRCode(photoBmp));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                    }

                    @Override
                    public void onCancel() {

                    }
                }));
    }

    private void getResult(String result) {
        List<String> list = new ArrayList<>();
        list.add(result);
        V2TIMManager.getInstance().getUsersInfo(list, new V2TIMValueCallback<List<V2TIMUserFullInfo>>() {
            @Override
            public void onSuccess(List<V2TIMUserFullInfo> v2TIMUserFullInfos) {
                Intent intent = new Intent(ScanActivity.this, FriendProfileActivity.class);
                intent.putExtra(TUIConstants.TUIChat.CHAT_ID, list.get(0));
                intent.putExtra(TUIConstants.TUIChat.CHAT_BACKGROUND_URI, v2TIMUserFullInfos.get(0).getFaceUrl());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }

            @Override
            public void onError(int i, String s) {
                Toast.makeText(ScanActivity.this, "用户不存在", Toast.LENGTH_LONG).show();
            }
        });
    }
    private String decodeQRCode(Bitmap qrCodeBitmap) {
        try {
            int[] pixels = new int[qrCodeBitmap.getWidth() * qrCodeBitmap.getHeight()];
            qrCodeBitmap.getPixels(pixels, 0, qrCodeBitmap.getWidth(), 0, 0,
                    qrCodeBitmap.getWidth(), qrCodeBitmap.getHeight());

            LuminanceSource source = new RGBLuminanceSource(
                    qrCodeBitmap.getWidth(),
                    qrCodeBitmap.getHeight(),
                    pixels
            );

            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(binaryBitmap);
            return result.getText();
        } catch (NotFoundException e) {
            Toast.makeText(this, "未识别到二维码", Toast.LENGTH_SHORT).show();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView == null) return;
        barcodeView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView == null) return;
        barcodeView.pause();
    }
}
