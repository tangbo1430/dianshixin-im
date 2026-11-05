package com.qchm.xyz.imaccess;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.qchm.xyz.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ImSessionRepository implements ImSessionProvider {
    // Keep startup responsive when the legacy business/IM gateway is unreachable.
    // A successful local session still opens immediately; only online refresh uses this limit.
    private static final long NETWORK_TIMEOUT_SECONDS = 5L;
    private final OkHttpClient client;
    private final Gson gson;
    private final String endpoint;

    public ImSessionRepository() {
        this(new OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(), new Gson(), BuildConfig.API_BASE_URL);
    }

    ImSessionRepository(OkHttpClient client, Gson gson, String baseUrl) {
        this.client = client;
        this.gson = gson;
        this.endpoint = trimTrailingSlash(baseUrl) + "/imaccess/session";
    }

    @Override
    public void issue(String loginToken, ImSessionProvider.Callback callback) {
        if (loginToken == null || loginToken.trim().isEmpty()) {
            callback.onError(new ImSessionException(401, "登录已失效", true));
            return;
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .post(new FormBody.Builder().add("login_token", loginToken.trim()).build())
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(new ImSessionException(502, "IM会话服务连接失败", false));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String raw = closeable.body() == null ? "" : closeable.body().string();
                    SessionEnvelope envelope = gson.fromJson(raw, SessionEnvelope.class);
                    int code = envelope == null ? closeable.code() : envelope.code;
                    if (!closeable.isSuccessful() || code != 200 || envelope == null || envelope.data == null) {
                        String message = envelope == null || isBlank(envelope.msg)
                                ? "IM会话签发失败"
                                : envelope.msg;
                        callback.onError(new ImSessionException(code, message, code == 401 || code == 403));
                        return;
                    }

                    SessionData data = envelope.data;
                    int sdkAppId = parseAppId(data.sdkAppId);
                    if (sdkAppId <= 0 || isBlank(data.imUserId) || isBlank(data.userSig) || data.expiresAt <= 0) {
                        callback.onError(new ImSessionException(502, "IM会话响应格式错误", false));
                        return;
                    }
                    callback.onSuccess(new ImSession(sdkAppId, data.imUserId, data.userSig, data.expiresAt));
                } catch (Exception ignored) {
                    callback.onError(new ImSessionException(502, "IM会话响应格式错误", false));
                }
            }
        });
    }

    private static int parseAppId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static class SessionEnvelope {
        int code;
        String msg;
        SessionData data;
    }

    private static class SessionData {
        @SerializedName("sdk_app_id")
        String sdkAppId;
        @SerializedName("im_user_id")
        String imUserId;
        @SerializedName("user_sig")
        String userSig;
        @SerializedName("expires_at")
        long expiresAt;
    }
}
