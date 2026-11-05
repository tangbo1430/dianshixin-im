package com.qchm.xyz.imaccess;

import com.tencent.imsdk.BaseConstants;

public final class ImSessionPolicy {
    public static final long REFRESH_WINDOW_SECONDS = 15 * 60;
    public static final long OFFLINE_GRACE_SECONDS = 7 * 24 * 60 * 60;

    private ImSessionPolicy() {}

    public static boolean isUsable(ImSession session, long nowSeconds) {
        return session != null
                && session.getSdkAppId() > 0
                && !isBlank(session.getImUserId())
                && !isBlank(session.getUserSig())
                && session.getExpiresAt() - nowSeconds > REFRESH_WINDOW_SECONDS;
    }

    public static boolean isUserSigError(int code) {
        return code == BaseConstants.ERR_USER_SIG_EXPIRED
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_EXPIRED
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_EMPTY
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_CHECK_FAILED
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_CHECK_FAILED_EX
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_MISMATCH_ID
                || code == BaseConstants.ERR_SVR_ACCOUNT_USERSIG_MISMATCH_SDKAPPID;
    }

    public static boolean canOpenLocal(ImSession session, long lastOnlineAuthAt, long nowSeconds) {
        return session != null
                && session.getSdkAppId() > 0
                && !isBlank(session.getImUserId())
                && lastOnlineAuthAt > 0
                && nowSeconds >= lastOnlineAuthAt
                && nowSeconds - lastOnlineAuthAt <= OFFLINE_GRACE_SECONDS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
