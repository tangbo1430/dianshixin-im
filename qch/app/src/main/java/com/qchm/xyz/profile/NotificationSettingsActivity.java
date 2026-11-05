package com.qchm.xyz.profile;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import com.qchm.xyz.R;
import com.tencent.qcloud.tuikit.timcommon.component.TitleBarLayout;
import com.tencent.qcloud.tuikit.timcommon.component.activities.BaseLightActivity;
import com.tencent.qcloud.tuikit.timcommon.component.interfaces.ITitleBarLayout;

import java.util.List;

public class NotificationSettingsActivity extends BaseLightActivity {
    private Switch allowSwitch;
    private Switch silentSwitch;
    private Switch bannerSwitch;
    private Switch vibrateSwitch;
    private Switch dndSwitch;
    private TextView lockScreenValue;
    private TextView ringtoneValue;
    private NotificationChannel primaryChannel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_settings);

        TitleBarLayout titleBar = findViewById(R.id.notification_title_bar);
        titleBar.setTitle("通知管理", ITitleBarLayout.Position.MIDDLE);
        titleBar.getRightGroup().setVisibility(View.GONE);
        titleBar.setOnLeftClickListener(v -> finish());

        TextView appName = findViewById(R.id.notification_app_name);
        appName.setText(getApplicationInfo().loadLabel(getPackageManager()));

        allowSwitch = findViewById(R.id.allow_notification_switch);
        silentSwitch = findViewById(R.id.silent_notification_switch);
        bannerSwitch = findViewById(R.id.banner_notification_switch);
        vibrateSwitch = findViewById(R.id.vibrate_notification_switch);
        dndSwitch = findViewById(R.id.dnd_notification_switch);
        lockScreenValue = findViewById(R.id.lock_screen_value);
        ringtoneValue = findViewById(R.id.ringtone_value);

        bindSystemSettingLink(R.id.allow_notification_row, true);
        bindSystemSettingLink(R.id.silent_notification_row, false);
        bindSystemSettingLink(R.id.banner_notification_row, false);
        bindSystemSettingLink(R.id.lock_screen_notification_row, false);
        bindSystemSettingLink(R.id.ringtone_notification_row, false);
        bindSystemSettingLink(R.id.vibrate_notification_row, false);
        bindSystemSettingLink(R.id.dnd_notification_row, false);
        allowSwitch.setOnClickListener(v -> openNotificationSettings(true));
        silentSwitch.setOnClickListener(v -> openNotificationSettings(false));
        bannerSwitch.setOnClickListener(v -> openNotificationSettings(false));
        vibrateSwitch.setOnClickListener(v -> openNotificationSettings(false));
        dndSwitch.setOnClickListener(v -> openNotificationSettings(false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNotificationState();
    }

    private void bindSystemSettingLink(int viewId, boolean appLevel) {
        findViewById(viewId).setOnClickListener(v -> openNotificationSettings(appLevel));
    }

    private void refreshNotificationState() {
        boolean appEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        allowSwitch.setChecked(appEnabled);
        primaryChannel = findPrimaryChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && primaryChannel != null) {
            boolean channelEnabled = primaryChannel.getImportance() != NotificationManager.IMPORTANCE_NONE;
            silentSwitch.setChecked(channelEnabled && primaryChannel.getSound() == null);
            bannerSwitch.setChecked(channelEnabled && primaryChannel.getImportance() >= NotificationManager.IMPORTANCE_HIGH);
            vibrateSwitch.setChecked(channelEnabled && primaryChannel.shouldVibrate());
            dndSwitch.setChecked(channelEnabled && primaryChannel.canBypassDnd());
            lockScreenValue.setText(primaryChannel.getLockscreenVisibility() == Notification.VISIBILITY_SECRET
                    ? "不显示" : "显示");
            ringtoneValue.setText(primaryChannel.getSound() == null ? "静音" : "默认铃声");
        } else {
            silentSwitch.setChecked(false);
            bannerSwitch.setChecked(appEnabled);
            vibrateSwitch.setChecked(appEnabled);
            dndSwitch.setChecked(false);
            lockScreenValue.setText(appEnabled ? "显示" : "关闭");
            ringtoneValue.setText(appEnabled ? "默认铃声" : "关闭");
        }

        float contentAlpha = appEnabled ? 1f : 0.55f;
        silentSwitch.setAlpha(contentAlpha);
        bannerSwitch.setAlpha(contentAlpha);
        vibrateSwitch.setAlpha(contentAlpha);
        dndSwitch.setAlpha(contentAlpha);
    }

    private NotificationChannel findPrimaryChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return null;
        }
        List<NotificationChannel> channels = manager.getNotificationChannels();
        if (channels == null || channels.isEmpty()) {
            return null;
        }
        NotificationChannel best = null;
        for (NotificationChannel channel : channels) {
            if (best == null || channel.getImportance() > best.getImportance()) {
                best = channel;
            }
        }
        return best;
    }

    private void openNotificationSettings(boolean appLevel) {
        Intent intent = new Intent();
        if (!appLevel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && primaryChannel != null) {
            intent.setAction(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, primaryChannel.getId());
        } else {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        }
        startActivity(intent);
    }
}
