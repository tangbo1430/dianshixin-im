package com.qchm.xyz.custom;

import android.content.Context;

import com.qchm.xyz.R;
import com.qchm.xyz.utils.BrandUtil;
import com.qchm.xyz.utils.Constants;
import com.tencent.qcloud.tuicore.TUIConfig;
import com.tencent.qcloud.tuicore.TUIThemeManager;
import com.tencent.qcloud.tuikit.tuichat.config.TUIChatConfigs;
import com.tencent.qcloud.tuikit.tuichat.config.classicui.TUIChatConfigClassic;
import com.tencent.qcloud.tuikit.tuichat.config.minimalistui.TUIChatConfigMinimalist;

public class CustomConfigHelper {
    private static Context mContext;

    public static void initCustom(Context context) {
        mContext = context;
        initConversationDefaultAvatar();
        initChatSettings();
    }

    public static void initConversationDefaultAvatar() {
        TUIConfig.setEnableGroupGridAvatar(true);
        TUIConfig.setDefaultAvatarImage(TUIThemeManager.getAttrResId(mContext, com.tencent.qcloud.tuikit.timcommon.R.attr.core_default_user_icon));
        TUIConfig.setDefaultGroupAvatarImage(TUIThemeManager.getAttrResId(mContext, com.tencent.qcloud.tuikit.timcommon.R.attr.core_default_group_icon_work));
    }

    public static void initChatSettings() {
        // Temporarily hide video capture while keeping album video sending available.
        TUIChatConfigClassic.hideItemsInMoreMenu(TUIChatConfigClassic.RECORD_VIDEO);
        TUIChatConfigMinimalist.hideItemsInMoreMenu(TUIChatConfigMinimalist.RECORD_VIDEO);
        if (BrandUtil.isSamsungS9Series()) {
            TUIChatConfigClassic.setUseSystemCamera(true);
            TUIChatConfigMinimalist.setUseSystemCamera(true);
        }
    }
}
