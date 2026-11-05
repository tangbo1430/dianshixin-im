package com.qchm.xyz.bj.view;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.qchm.xyz.R;

import org.jetbrains.annotations.Nullable;

public class X5WebViewActivity extends BaseLightActivity {
    X5WebView webView;
    TextView tv_main_title;
    View iv_base_back;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x5webview);
        webView = findViewById(R.id.x5wv_view);
        tv_main_title = findViewById(R.id.tv_main_title);
        iv_base_back = findViewById(R.id.iv_base_back);
        tv_main_title.setText(getIntent().getStringExtra("title"));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                webView.loadUrl(url);
                return true;
            }
        });
        webView.loadUrl(getIntent().getStringExtra("url"));
        iv_base_back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
