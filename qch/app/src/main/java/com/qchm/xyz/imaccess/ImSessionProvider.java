package com.qchm.xyz.imaccess;

public interface ImSessionProvider {
    interface Callback {
        void onSuccess(ImSession session);

        void onError(ImSessionException error);
    }

    void issue(String loginToken, Callback callback);
}
