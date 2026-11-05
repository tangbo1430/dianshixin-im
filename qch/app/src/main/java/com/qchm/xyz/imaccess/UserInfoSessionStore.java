package com.qchm.xyz.imaccess;

import com.qchm.xyz.bean.UserInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class UserInfoSessionStore implements ImSessionStore {
    @Override
    public String getLoginToken() {
        return UserInfo.getInstance().getUserToken();
    }

    @Override
    public ImSession getSession() {
        UserInfo info = UserInfo.getInstance();
        return new ImSession(info.getSdkAppId(), info.getUserId(), info.getUserSig(), info.getUserSigExpiresAt());
    }

    @Override
    public boolean isAutoLogin() {
        return UserInfo.getInstance().isAutoLogin();
    }

    @Override
    public long getLastOnlineAuthAt() {
        return UserInfo.getInstance().getLastOnlineAuthAt();
    }

    @Override
    public void saveLoginToken(String loginToken) {
        UserInfo.getInstance().setUserToken(loginToken);
    }

    @Override
    public void saveSession(ImSession session) {
        UserInfo.getInstance().setImSession(session.getSdkAppId(), session.getImUserId(),
                session.getUserSig(), session.getExpiresAt());
    }

    @Override
    public void saveSession(ImSession session, String loginToken) {
        UserInfo.getInstance().setImSession(session.getSdkAppId(), session.getImUserId(),
                session.getUserSig(), session.getExpiresAt(), tokenFingerprint(loginToken));
    }

    @Override
    public boolean isSessionForToken(String loginToken) {
        String stored = UserInfo.getInstance().getImSessionOwner();
        // Old installations have no owner marker. They are refreshed once before reuse.
        return !stored.isEmpty() && stored.equals(tokenFingerprint(loginToken));
    }

    @Override
    public void setAutoLogin(boolean autoLogin) {
        UserInfo.getInstance().setAutoLogin(autoLogin);
    }

    @Override
    public void setLastOnlineAuthAt(long timestampSeconds) {
        UserInfo.getInstance().setLastOnlineAuthAt(timestampSeconds);
    }

    @Override
    public void clear() {
        UserInfo.getInstance().cleanUserInfo();
    }

    private static String tokenFingerprint(String token) {
        if (token == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
