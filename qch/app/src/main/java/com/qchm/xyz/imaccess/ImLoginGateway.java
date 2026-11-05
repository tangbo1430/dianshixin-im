package com.qchm.xyz.imaccess;

import android.content.Context;

public interface ImLoginGateway {
    interface Callback {
        void onSuccess();

        void onError(int code, String message);
    }

    void login(Context context, ImSession session, Callback callback);

    void openLocal(Context context, ImSession session, Callback callback);
}
