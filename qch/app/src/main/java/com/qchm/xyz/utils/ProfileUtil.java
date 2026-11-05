package com.qchm.xyz.utils;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import com.qchm.xyz.bean.UserInfo;
import com.qchm.xyz.bj.LoginActivity;
import com.qchm.xyz.imaccess.ImLoginCoordinator;

public class ProfileUtil {
    public static final boolean DEFAULT_IS_OPEN_MESSAGE_READ_RECEIPT = false;

    public static void onLogoutSuccess(Activity activity) {
        ImLoginCoordinator.getInstance().clearSessionState();
        UserInfo.getInstance().cleanUserInfo();
        if (activity != null) {
            Intent intent = new Intent(activity, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Constants.LOGOUT, true);
            activity.startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());

        }
    }

    public static void setTestEntry(View view) {
        // do nothing
    }
}
