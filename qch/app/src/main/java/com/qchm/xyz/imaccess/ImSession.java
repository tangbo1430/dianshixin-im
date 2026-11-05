package com.qchm.xyz.imaccess;

public class ImSession {
    private final int sdkAppId;
    private final String imUserId;
    private final String userSig;
    private final long expiresAt;

    public ImSession(int sdkAppId, String imUserId, String userSig, long expiresAt) {
        this.sdkAppId = sdkAppId;
        this.imUserId = imUserId;
        this.userSig = userSig;
        this.expiresAt = expiresAt;
    }

    public int getSdkAppId() {
        return sdkAppId;
    }

    public String getImUserId() {
        return imUserId;
    }

    public String getUserSig() {
        return userSig;
    }

    public long getExpiresAt() {
        return expiresAt;
    }
}
