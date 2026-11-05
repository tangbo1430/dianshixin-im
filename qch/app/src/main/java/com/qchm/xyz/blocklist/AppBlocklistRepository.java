package com.qchm.xyz.blocklist;

import android.os.Handler;
import android.os.Looper;
import com.qchm.xyz.BuildConfig;
import com.qchm.xyz.bean.UserInfo;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

public class AppBlocklistRepository {
    public interface Callback {
        void onSuccess(JSONObject response);
        void onError(int code, String message);
    }

    private final OkHttpClient client;
    private final String baseUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AppBlocklistRepository() {
        this(new OkHttpClient(), BuildConfig.API_BASE_URL);
    }

    AppBlocklistRepository(OkHttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public void status(String peerAccount, Callback callback) {
        execute("/imaccess/blocklist/status", peerAccount, null, callback);
    }

    public void change(String peerAccount, boolean blocked, Callback callback) {
        execute("/imaccess/blocklist/change", peerAccount, blocked, callback);
    }

    public void listing(Callback callback) {
        execute("/imaccess/blocklist/listing", null, null, callback);
    }

    private void execute(String path, String peerAccount, Boolean blocked, Callback callback) {
        String token = UserInfo.getInstance().getUserToken();
        if (token == null || token.trim().isEmpty()) {
            callback.onError(401, "登录已失效");
            return;
        }

        FormBody.Builder body = new FormBody.Builder().add("login_token", token.trim());
        if (peerAccount != null) {
            body.add("peer_account", peerAccount.trim());
        }
        if (blocked != null) {
            body.add("blocked", blocked ? "1" : "0");
        }
        Request request = new Request.Builder().url(baseUrl + path).post(body.build()).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                deliverError(callback, 502, "屏蔽服务连接失败");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String raw = closeable.body() == null ? "" : closeable.body().string();
                    JSONObject json = new JSONObject(raw);
                    int code = json.optInt("code", closeable.code());
                    if (!closeable.isSuccessful() || code != 200) {
                        deliverError(callback, code, json.optString("msg", "屏蔽操作失败"));
                        return;
                    }
                    mainHandler.post(() -> callback.onSuccess(json));
                } catch (IOException | JSONException e) {
                    deliverError(callback, 502, "屏蔽服务响应异常");
                }
            }
        });
    }

    private void deliverError(Callback callback, int code, String message) {
        mainHandler.post(() -> callback.onError(code, message));
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
