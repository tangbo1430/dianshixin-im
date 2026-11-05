package com.qchm.xyz.blocklist;

import com.tencent.imsdk.v2.V2TIMFriendInfo;
import com.tencent.imsdk.v2.V2TIMFriendOperationResult;
import com.tencent.imsdk.v2.V2TIMManager;
import com.tencent.imsdk.v2.V2TIMValueCallback;
import java.util.ArrayList;
import java.util.List;

public final class LegacyTencentBlocklistCleaner {
    private LegacyTencentBlocklistCleaner() {}

    public static void clean() {
        V2TIMManager.getFriendshipManager().getBlackList(new V2TIMValueCallback<List<V2TIMFriendInfo>>() {
            @Override
            public void onSuccess(List<V2TIMFriendInfo> list) {
                if (list == null || list.isEmpty()) {
                    return;
                }
                List<String> accounts = new ArrayList<>();
                for (V2TIMFriendInfo item : list) {
                    if (item != null && item.getUserID() != null && !item.getUserID().isEmpty()) {
                        accounts.add(item.getUserID());
                    }
                }
                if (accounts.isEmpty()) {
                    return;
                }
                V2TIMManager.getFriendshipManager().deleteFromBlackList(
                        accounts,
                        new V2TIMValueCallback<List<V2TIMFriendOperationResult>>() {
                            @Override
                            public void onSuccess(List<V2TIMFriendOperationResult> results) {}

                            @Override
                            public void onError(int code, String desc) {}
                        });
            }

            @Override
            public void onError(int code, String desc) {}
        });
    }
}
