package dev.icos.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import dev.icos.app.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Replaces only the Activity's test content; never changes legal/network consent or signs in. */
class AvatarMediaUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: MainActivity
    private lateinit var bitmap: Bitmap
    private val selected = AtomicInteger()
    @Volatile private var current = true
    @Volatile private var selectedUri: android.net.Uri? = null
    private var previousPreferences: Map<String, *> = emptyMap<String, Any>()
    private var preferencesCaptured = false

    @Before fun openIsolatedProductionComponent() {
        assumeTrue("Run UI permission cases separately with icosAvatarUi=true and reset this app's photo grants between cases",
            InstrumentationRegistry.getArguments().getString("icosAvatarUi") == "true")
        val preferences = context.getSharedPreferences("avatar_media_consent", Context.MODE_PRIVATE)
        previousPreferences = preferences.all
        preferencesCaptured = true
        assertTrue(preferences.edit().clear().commit())
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        bitmap = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        instrumentation.runOnMainSync {
            activity.setContent {
                IcosTheme(AppearanceSettings()) {
                    Column {
                        Text("Avatar component instrumentation test")
                        AvatarMediaActions(bitmap, true, { current }) {
                            selectedUri = it
                            selected.incrementAndGet()
                        }
                    }
                }
            }
        }
        waitText("选择图片并上传头像")
    }

    @After fun closeAndRestorePreferences() {
        if (!preferencesCaptured) return
        current = false
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        if (::bitmap.isInitialized) bitmap.recycle()
        val editor = context.getSharedPreferences("avatar_media_consent", Context.MODE_PRIVATE).edit().clear()
        previousPreferences.forEach { (key, value) ->
            check(value is Boolean) { "Unexpected avatar preference type" }
            editor.putBoolean(key, value)
        }
        assertTrue(editor.commit())
    }

    @Test fun saveConsentCancellationAndStaleSessionDoNotWriteAndApprovedSaveIsDecodable() {
        val before = images()
        clickText("保存头像到相册")
        waitText("保存图片到相册授权")
        screenshot("dsx-avatar-save-explanation")
        assertEquals(before, images())
        clickText("取消")
        waitText("已取消授权，未保存图片。")
        assertEquals(before, images())
        clickText("保存头像到相册")
        clickText("同意并继续")
        waitText("已保存到相册“店时信”。")
        screenshot("dsx-avatar-save-success")
        val created = images() - before
        assertEquals(1, created.size)
        try {
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, created.single())
            val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
            try {
                assertEquals(9, decoded.width)
                assertEquals(5, decoded.height)
                assertEquals(Color.CYAN, decoded.getPixel(0, 0))
            } finally { decoded.recycle() }
            current = false
            clickText("保存头像到相册")
            waitText("账号或头像状态已变化，请重新打开头像后重试。")
            assertEquals(1, (images() - before).size)
            assertEquals(0, selected.get())
        } finally {
            created.forEach { id ->
                context.contentResolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null)
            }
        }
    }

    @Test fun uploadExplanationCancellationAndSystemDenialDoNotReadOrUpload() {
        clickText("选择图片并上传头像")
        waitText("照片读取与头像上传授权")
        screenshot("dsx-avatar-upload-explanation")
        clickText("取消")
        waitText("已取消授权，未读取或上传图片。")
        assertEquals(0, selected.get())
        repeat(2) { attempt ->
            clickText("选择图片并上传头像")
            clickText("同意并继续")
            val deny = waitNode { it.viewIdResourceName?.contains(":id/permission_deny") == true }
            screenshot("dsx-avatar-system-photos-permission-$attempt")
            click(deny)
            if (attempt == 0) waitText("未获得照片授权，未读取或上传图片，其他功能不受影响。")
            else waitText("权限未开启")
            assertEquals(0, selected.get())
        }
        waitText("权限未开启")
        screenshot("dsx-avatar-permission-settings")
        clickText("暂不开启")
        assertEquals(0, selected.get())
    }

    @Test fun systemPhotoGrantOpensPickerAndOnlySelectedImageIsReturned() {
        val seed = AvatarImages.saveToAlbum(context, bitmap) { true }
        val name = context.contentResolver.query(seed, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
        try {
            clickText("选择图片并上传头像")
            clickText("同意并继续")
            click(waitNode { it.viewIdResourceName?.endsWith(":id/permission_allow_all_button") == true })
            waitPicker()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitText("已取消选择，未上传图片。")
            assertEquals(0, selected.get())
            clickText("选择图片并上传头像")
            click(waitNode { it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true ||
                it.text?.contains(name) == true || it.contentDescription?.contains(name) == true })
            waitUntil { selected.get() == 1 }
            val bytes = AvatarImages.readUpload(context, checkNotNull(selectedUri))
            val decoded = checkNotNull(dev.icos.app.data.remote.AvatarBmp.decode(bytes))
            try {
                assertEquals(9, decoded.width)
                assertEquals(5, decoded.height)
                assertEquals(Color.CYAN, decoded.getPixel(0, 0))
            } finally { decoded.recycle() }
            clickText("选择图片并上传头像")
            waitPicker()
            current = false
            click(waitNode { it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true ||
                it.text?.contains(name) == true || it.contentDescription?.contains(name) == true })
            waitText("照片授权或账号状态已变化，未上传图片，请重试。")
            assertEquals(1, selected.get())
        } finally { assertEquals(1, context.contentResolver.delete(seed, null, null)) }
    }

    @Test fun partialPhotoGrantOpensPickerWithoutFullLibraryPermission() {
        val seed = AvatarImages.saveToAlbum(context, bitmap) { true }
        try {
            clickText("选择图片并上传头像")
            clickText("同意并继续")
            click(waitNode { it.viewIdResourceName?.endsWith(":id/permission_allow_selected_button") == true })
            waitNode { it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true }
            click(waitNode { it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true })
            click(waitNode { it.viewIdResourceName?.endsWith(":id/button_add") == true })
            waitPicker()
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES))
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
            current = false
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitText("已取消选择，未上传图片。")
            assertEquals(0, selected.get())
        } finally { assertEquals(1, context.contentResolver.delete(seed, null, null)) }
    }

    private fun images(): Set<Long> = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?", arrayOf("Pictures/店时信/"), null,
    )!!.use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil {
            result = automation.windows.asSequence().mapNotNull { find(it.root, predicate) }.firstOrNull()
                ?: find(automation.rootInActiveWindow, predicate)
            result != null
        }
        return checkNotNull(result)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        screenshot("dsx-avatar-test-failure")
        error("Avatar UI condition not met within 15 seconds")
    }

    private fun waitText(text: String) = waitNode { it.text?.toString() == text }
    private fun waitPicker() = waitNode {
        it.packageName?.toString()?.contains("documentsui") == true || it.viewIdResourceName?.endsWith(":id/icon_thumbnail") == true
    }
    private fun clickText(text: String) = click(waitText(text))
    private fun click(node: AccessibilityNodeInfo) {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = candidate.parent
        }
        error("No clickable ancestor for ${node.text}")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        fun tree(node: AccessibilityNodeInfo?, depth: Int, lines: MutableList<String>) {
            if (node == null) return
            lines += "${" ".repeat(depth)}${node.viewIdResourceName}: ${node.text} [${node.contentDescription}]"
            for (index in 0 until node.childCount) tree(node.getChild(index), depth + 1, lines)
        }
        val lines = mutableListOf<String>()
        automation.windows.forEach { tree(it.root, 0, lines) }
        File(context.getExternalFilesDir(null), "$name.txt").writeText(lines.joinToString("\n"))
        val screenshot = checkNotNull(automation.takeScreenshot())
        try {
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
}
