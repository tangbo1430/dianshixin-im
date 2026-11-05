package com.qchm.xyz.imaccess;

public interface ImSessionStore {
    String getLoginToken();

    ImSession getSession();

    boolean isAutoLogin();

    long getLastOnlineAuthAt();

    void saveLoginToken(String loginToken);

    void saveSession(ImSession session);

    /**
     * Associates the cached IM credentials with the business login that issued them.
     * Implementations used by older tests can keep the legacy behavior.
     */
    default void saveSession(ImSession session, String loginToken) {
        saveSession(session);
    }

    /** Returns false when cached IM credentials belong to another business account. */
    default boolean isSessionForToken(String loginToken) {
        return true;
    }

    void setAutoLogin(boolean autoLogin);

    void setLastOnlineAuthAt(long timestampSeconds);

    void clear();
}
