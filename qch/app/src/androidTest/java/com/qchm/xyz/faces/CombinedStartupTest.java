package com.qchm.xyz.faces;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import com.qchm.xyz.SplashActivity;
import com.qchm.xyz.bj.LoginActivity;
import com.qchm.xyz.bean.UserInfo;
import dev.icos.app.MainActivity;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Exercises real failure/cache startup, not a substitute for live COS A/B acceptance. */
public class CombinedStartupTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> activities = new ArrayList<>();
    private final List<Instrumentation.ActivityMonitor> monitors = new ArrayList<>();
    private SharedPreferences cache;
    private String previousRaw;
    private boolean hadRaw;
    private int previousFlags;
    private boolean remoteAbsent;
    private FacePolicy remotePolicy;

    @Before public void requireExplicitOptInAndInspectConfiguration() throws Exception {
        assumeTrue("Only run explicitly on a test device",
                "true".equals(InstrumentationRegistry.getArguments().getString("icosAbUi")));
        assumeTrue("Never disturb a signed-in IM session", !UserInfo.getInstance().isAutoLogin());
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder().url(FaceEntryActivity.CONFIG_URL)
                .header("Cache-Control", "no-cache").build()).execute()) {
            remoteAbsent = response.code() == 404;
            remotePolicy = response.isSuccessful() && response.body() != null
                    ? FacePolicy.parse(response.body().string()) : null;
        }
        cache = instrumentation.getTargetContext().getSharedPreferences("dsx_face_policy_v1", Context.MODE_PRIVATE);
        hadRaw = cache.contains("raw");
        previousRaw = cache.getString("raw", null);
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
    }

    @After public void restoreOnlyTestOwnedChanges() {
        for (Activity activity : activities) instrumentation.runOnMainSync(activity::finish);
        for (Instrumentation.ActivityMonitor monitor : monitors) instrumentation.removeMonitor(monitor);
        if (cache != null) {
            SharedPreferences.Editor editor = cache.edit();
            if (hadRaw) editor.putString("raw", previousRaw); else editor.remove("raw");
            assertTrue(editor.commit());
            AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
            info.flags = previousFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    @Test public void unavailableCosWithoutCacheEntersActualStoreActivity() {
        assumeTrue("Requires real COS 404; not simulated", remoteAbsent);
        assertTrue(cache.edit().remove("raw").commit());
        Instrumentation.ActivityMonitor store = monitor(MainActivity.class);
        launch();
        await(store);
    }

    @Test public void unavailableCosWithCachedBShowsAgreementAndContinuesToLogin() {
        assumeTrue("Requires real COS 404; not simulated", remoteAbsent);
        SplashActivity splash = cachedB();
        assertAgreement();
        Instrumentation.ActivityMonitor login = monitor(LoginActivity.class);
        click("rightText");
        Activity activity = await(login);
        assertTrue(activity instanceof LoginActivity);
        assertTrue(splash.isFinishing() || splash.isDestroyed());
    }

    @Test public void refusingCachedBAgreementDoesNotStartLogin() {
        assumeTrue("Requires real COS 404; not simulated", remoteAbsent);
        SplashActivity splash = cachedB();
        assertAgreement();
        Instrumentation.ActivityMonitor login = monitor(LoginActivity.class);
        click("leftText");
        instrumentation.waitForIdleSync();
        assertNull(login.waitForActivityWithTimeout(1500));
        assertTrue(splash.isFinishing() || splash.isDestroyed());
    }

    @Test public void liveCosRoutesToExactDeclaredActivity() {
        assertNotNull("Live COS must contain a valid unified configuration", remotePolicy);
        assertTrue(cache.edit().remove("raw").commit());
        Instrumentation.ActivityMonitor destination = monitor(FaceEntryActivity.targetClass(remotePolicy));
        launch();
        await(destination);
        assertNotNull("Successful live config must be persisted, not fallback-only",
                FacePolicy.parse(cache.getString("raw", "")));
        assertEquals(remotePolicy.useA(), FacePolicy.parse(cache.getString("raw", "")).useA());
        if (!remotePolicy.useA()) assertAgreement();
    }

    @Test public void directlyOpenedChatAgreementContinuesToLogin() throws Exception {
        SplashActivity splash = openChat();
        assertAgreement();
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                instrumentation.getTargetContext().getExternalFilesDir(null), "dsx-chat-agreement.png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
        Instrumentation.ActivityMonitor login = monitor(LoginActivity.class);
        click("rightText");
        await(login);
        assertTrue(splash.isFinishing() || splash.isDestroyed());
    }

    @Test public void directlyOpenedChatAgreementRefusalDoesNotStartLogin() {
        SplashActivity splash = openChat();
        assertAgreement();
        Instrumentation.ActivityMonitor login = monitor(LoginActivity.class);
        click("leftText");
        instrumentation.waitForIdleSync();
        assertNull(login.waitForActivityWithTimeout(1500));
        assertTrue(splash.isFinishing() || splash.isDestroyed());
    }

    private void assertAgreement() {
        assertTrue(waitText("用户协议与隐私政策"));
        assertTrue(waitText("同意并继续"));
        assertTrue(waitText("不同意"));
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        for (String obsolete : new String[] {
                "测试", "屋六信", "尚未接入", "尚未生效", "业务后端"
        }) assertTrue("Obsolete notice must not be visible: " + obsolete,
                root.findAccessibilityNodeInfosByText(obsolete).isEmpty());
    }

    private SplashActivity openChat() {
        SplashActivity splash = (SplashActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), SplashActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(splash);
        return splash;
    }

    private SplashActivity cachedB() {
        assertTrue(cache.edit().putString("raw", "CHAOXXMAI_V3_PROPERTY_A_ENABLED:0").commit());
        Instrumentation.ActivityMonitor splash = monitor(SplashActivity.class);
        launch();
        return (SplashActivity) await(splash);
    }

    private void launch() {
        activities.add(instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                FaceEntryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private Instrumentation.ActivityMonitor monitor(Class<? extends Activity> type) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(type.getName(), null, false);
        monitors.add(monitor);
        return monitor;
    }

    private Activity await(Instrumentation.ActivityMonitor monitor) {
        Activity activity = monitor.waitForActivityWithTimeout(12000);
        assertNotNull("Expected real target Activity within startup timeout", activity);
        activities.add(activity);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private boolean waitText(String text) {
        long end = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty()) return true;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        return false;
    }

    private void click(String id) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull(root);
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                instrumentation.getTargetContext().getPackageName() + ":id/" + id);
        assertEquals(1, nodes.size());
        assertTrue(nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
}
