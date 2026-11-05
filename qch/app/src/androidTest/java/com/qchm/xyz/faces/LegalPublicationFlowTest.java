package com.qchm.xyz.faces;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses the installed app's real first-run UI and published HTTP documents, never setContent. */
public class LegalPublicationFlowTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void liveDocumentsOpenInFullAndOnlyExplicitConsentEnablesNetwork() throws Exception {
        assumeTrue("Requires an explicitly selected test device",
                "true".equals(InstrumentationRegistry.getArguments().getString("icosLegalUi")));
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("legal_consent", Context.MODE_PRIVATE);
        Map<String, ?> before = preferences.getAll();
        JSONObject privacy = document("privacy");
        JSONObject terms = document("terms");
        assumeTrue("Use first-run state or a real published-version update, never reset existing choices",
                !preferences.contains("network_allowed") ||
                        (preferences.getBoolean("network_allowed", false) &&
                                (preferences.getLong("privacy_version", 0) != privacy.getLong("version") ||
                                        preferences.getLong("terms_version", 0) != terms.getLong("version"))));
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int previousFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        Activity activity = null;
        android.app.Instrumentation.ActivityMonitor destination = instrumentation.addMonitor(
                "dev.icos.app.MainActivity", null, false);
        try {
            instrumentation.startActivitySync(new Intent(context, FaceEntryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = destination.waitForActivityWithTimeout(15000);
            assertNotNull("Real launcher must enter the store activity", activity);
            waitText("选择你的使用方式");
            AccessibilityNodeInfo proceed = clickable(waitText("同意并开启联网使用"));
            assertFalse("Must not enable network before checking consent", proceed.isEnabled());
            assertEquals("Loading must not change previously recorded consent", before, preferences.getAll());
            openAndCheck(context, "用户协议", terms);
            openAndCheck(context, "隐私政策", privacy);
            screenshot(context, "dsx-legal-ready");
            click("我已阅读并同意《用户协议》和《隐私政策》");
            long enabledDeadline = SystemClock.uptimeMillis() + 5000;
            while (!clickable(waitText("同意并开启联网使用")).isEnabled()
                    && SystemClock.uptimeMillis() < enabledDeadline) SystemClock.sleep(100);
            assertTrue("Proceed must enable after the explicit checkbox action",
                    clickable(waitText("同意并开启联网使用")).isEnabled());
            assertEquals("Checking a box alone must not persist new consent", before, preferences.getAll());
            click("同意并开启联网使用");
            long end = SystemClock.uptimeMillis() + 10000;
            while (!preferences.getBoolean("network_allowed", false) && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(100);
            }
            assertTrue("Real UI must save consent", preferences.getBoolean("network_allowed", false));
            checkConsent(preferences, "privacy", privacy);
            checkConsent(preferences, "terms", terms);
            long continuationDeadline = SystemClock.uptimeMillis() + 15000;
            while (find("选择你的使用方式") != null
                    && SystemClock.uptimeMillis() < continuationDeadline) SystemClock.sleep(150);
            screenshot(context, "dsx-legal-continued");
            assertNull("First-run agreement gate must close after explicit consent", find("选择你的使用方式"));
        } finally {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
            }
            instrumentation.removeMonitor(destination);
            instrumentation.waitForIdleSync();
            SystemClock.sleep(500);
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : before.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Long) restore.putLong(entry.getKey(), (Long) value);
                else if (value instanceof String) restore.putString(entry.getKey(), (String) value);
                else fail("Unexpected legal consent preference type");
            }
            assertTrue(restore.commit());
            info.flags = previousFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    private JSONObject document(String kind) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build();
        try (Response response = client.newCall(new Request.Builder()
                .url("https://www.xmjcmy.com:18445/v1/legal/" + kind + "?locale=zh-CN")
                .header("Cache-Control", "no-cache").build()).execute()) {
            assertEquals(200, response.code());
            assertNotNull(response.body());
            JSONObject data = new JSONObject(response.body().string()).getJSONObject("data");
            assertEquals(kind, data.getString("kind"));
            assertTrue(data.getString("content").contains("金华市好运发商务信息咨询有限责任公司"));
            assertTrue(data.getString("content").contains("arkplustech@gmail.com"));
            for (String obsolete : new String[] {
                    "待确认稿", "测试稿", "本稿", "源码检查", "等待接入",
                    "18445", "admin.awhaha.com", "config.xmjcmy.com",
                    "tiuibipl.txt", "启动配置", "统一功能配置", "业务接口"
            }) assertFalse("Obsolete implementation or draft text: " + obsolete,
                    data.getString("content").contains(obsolete));
            return data;
        }
    }

    private void openAndCheck(Context context, String name, JSONObject document) throws Exception {
        click(name);
        waitText("关闭");
        assertNotNull("Document title must use the user-facing name", waitText(name));
        assertNotNull("Displayed full text must exactly match the published API content",
                waitText(document.getString("content")));
        assertNull("Publication version must not appear in the document title",
                find(name + " v" + document.getLong("version")));
        assertNull("Publication summary must not be added above the document",
                find("变更摘要：" + document.getString("changeSummary")));
        screenshot(context, "dsx-legal-" + document.getString("kind"));
        click("关闭");
        waitText("选择你的使用方式");
    }

    private void checkConsent(SharedPreferences preferences, String kind, JSONObject data) throws Exception {
        assertEquals(data.getLong("version"), preferences.getLong(kind + "_version", 0));
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                data.getString("content").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder digest = new StringBuilder();
        for (byte value : bytes) digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertEquals(digest.toString(), preferences.getString(kind + "_digest", null));
    }

    private AccessibilityNodeInfo find(String text) {
        AccessibilityNodeInfo active = find(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
        if (active != null) return active;
        for (android.view.accessibility.AccessibilityWindowInfo window :
                instrumentation.getUiAutomation().getWindows()) {
            AccessibilityNodeInfo found = find(window.getRoot(), text);
            if (found != null) return found;
        }
        return find(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = find(node.getChild(index), text);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo waitText(String text) {
        long end = SystemClock.uptimeMillis() + 15000;
        do {
            AccessibilityNodeInfo node = find(text);
            if (node != null) return node;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected first-run legal UI element was not displayed");
    }

    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        while (node != null && !node.isClickable()) node = node.getParent();
        assertNotNull("Expected a clickable parent", node);
        return node;
    }

    private void click(String text) {
        AccessibilityNodeInfo node = clickable(waitText(text));
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (!node.isEnabled() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            node = clickable(waitText(text));
        }
        assertTrue("Cannot tap a disabled control", node.isEnabled());
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        long down = SystemClock.uptimeMillis();
        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down,
                android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 50,
                android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        try {
            instrumentation.sendPointerSync(press);
            instrumentation.sendPointerSync(release);
        } finally {
            press.recycle();
            release.recycle();
        }
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
    }

    private void screenshot(Context context, String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            image.recycle();
        }
    }
}
