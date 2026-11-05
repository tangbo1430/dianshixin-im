package com.qchm.xyz.faces;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import com.qchm.xyz.R;
import com.qchm.xyz.SplashActivity;
import dev.icos.app.MainActivity;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class FaceEntryActivity extends Activity {
    static final String CONFIG_URL = "https://config.xmjcmy.com/dianshixin/tiuibipl.txt";
    private static final String CACHE_NAME = "dsx_face_policy_v1";
    private static final int MAX_CONFIG_BYTES = 16 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).build();
    private Call request;
    private boolean routed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_splash);
        SharedPreferences cache = getSharedPreferences(CACHE_NAME, MODE_PRIVATE);
        request = CLIENT.newCall(new Request.Builder().url(CONFIG_URL).header("Cache-Control", "no-cache").build());
        request.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                if (!call.isCanceled()) fallback(cache, error);
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful()) throw new IOException("Startup config HTTP " + closeable.code());
                    ResponseBody body = closeable.body();
                    if (body == null || body.contentLength() > MAX_CONFIG_BYTES) throw new IOException("Invalid startup config length");
                    if (body.source().request(MAX_CONFIG_BYTES + 1L)) throw new IOException("Startup config exceeds size limit");
                    byte[] bytes = body.source().getBuffer().readByteArray();
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    FacePolicy policy = FacePolicy.parse(text);
                    if (policy == null) throw new IOException("Invalid startup config");
                    if (!cache.edit().putString("raw", text).commit()) Log.w("DsxStartup", "Cannot persist startup config");
                    selectFace(policy, null);
                } catch (IOException error) {
                    fallback(cache, error);
                }
            }
        });
    }

    private void fallback(SharedPreferences cache, IOException error) {
        Log.w("DsxStartup", "Startup config unavailable", error);
        FacePolicy cached = FacePolicy.parse(cache.getString("raw", ""));
        selectFace(cached, "网络连接异常，请稍后重试。");
    }

    private void selectFace(FacePolicy policy, String notice) {
        runOnUiThread(() -> {
            if (routed || isFinishing() || isDestroyed()) return;
            routed = true;
            if (notice != null) Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
            Intent target = new Intent(this, targetClass(policy));
            startActivity(target);
            finish();
        });
    }

    static Class<? extends Activity> targetClass(FacePolicy policy) {
        return policy == null || policy.useA() ? MainActivity.class : SplashActivity.class;
    }

    @Override protected void onDestroy() {
        if (request != null) request.cancel();
        super.onDestroy();
    }
}
