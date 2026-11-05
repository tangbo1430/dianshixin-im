package dev.icos.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private enum class AvatarMediaAction { UPLOAD, SAVE }

@Composable
internal fun AvatarMediaActions(
    bitmap: Bitmap?,
    enabled: Boolean,
    isCurrent: () -> Boolean,
    onSelected: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val currentCheck by rememberUpdatedState(isCurrent)
    val selected by rememberUpdatedState(onSelected)
    val currentBitmap by rememberUpdatedState(bitmap)
    val currentlyEnabled by rememberUpdatedState(enabled)
    val active = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    val preferences = remember { context.getSharedPreferences("avatar_media_consent", Context.MODE_PRIVATE) }
    var explanation by remember { mutableStateOf<AvatarMediaAction?>(null) }
    var pending by remember { mutableStateOf<AvatarMediaAction?>(null) }
    var settingsNeeded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var savingBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun report(message: String, error: Exception? = null) {
        status = message
        if (error != null) Log.w("AvatarMedia", message, error)
    }

    fun usable(): Boolean {
        if (active.get() && currentlyEnabled && currentCheck()) return true
        report("账号或头像状态已变化，请重新打开头像后重试。")
        return false
    }

    fun hasReadPermission(): Boolean = AvatarMediaPolicy.readPermissions(Build.VERSION.SDK_INT).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun save() {
        val source = savingBitmap
        if (source == null || !usable() || currentBitmap !== source) {
            report("头像已变化，未保存图片，请重新选择保存。")
            savingBitmap = null
            return
        }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AvatarImages.saveToAlbum(context, source) {
                        active.get() && currentCheck() && currentBitmap === source
                    }
                }
                report("已保存到相册“店时信”。")
            } catch (error: IOException) {
                report("保存失败，请检查相册空间后重试。", error)
            } catch (error: SecurityException) {
                report("相册写入权限不可用，未完成保存，请重新授权。", error)
            } catch (error: IllegalStateException) {
                report("账号或头像状态已变化，已取消保存。", error)
            } finally {
                saving = false
                savingBitmap = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val expected = pending
        pending = null
        if (expected != AvatarMediaAction.UPLOAD) {
            report("选图操作已过期，请重新选择头像。")
        } else if (uri == null) {
            report("已取消选择，未上传图片。")
        } else if (usable() && hasReadPermission()) {
            selected(uri)
        } else {
            report("照片授权或账号状态已变化，未上传图片，请重试。")
        }
    }

    fun openPicker() {
        if (!usable()) { pending = null; return }
        pending = AvatarMediaAction.UPLOAD
        try {
            picker.launch("image/*")
        } catch (error: ActivityNotFoundException) {
            pending = null
            report("无法打开系统图片选择器，请检查设备设置。", error)
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        if (action == null || !usable()) {
            report("授权操作已取消，请重新操作。")
        } else {
            val granted = if (action == AvatarMediaAction.UPLOAD) hasReadPermission() else
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                if (action == AvatarMediaAction.UPLOAD) openPicker() else save()
            } else {
                savingBitmap = null
                report(if (action == AvatarMediaAction.UPLOAD) "未获得照片授权，未读取或上传图片，其他功能不受影响。" else
                    "未获得相册写入授权，未保存图片，其他功能不受影响。")
                val requested = if (action == AvatarMediaAction.UPLOAD) AvatarMediaPolicy.readPermissions(Build.VERSION.SDK_INT)
                    else arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                settingsNeeded = activity != null && requested.none { activity.shouldShowRequestPermissionRationale(it) }
            }
        }
    }

    fun proceed(action: AvatarMediaAction) {
        if (!usable()) return
        if (action == AvatarMediaAction.UPLOAD && hasReadPermission()) {
            openPicker()
        } else if (action == AvatarMediaAction.SAVE &&
            (!AvatarMediaPolicy.needsWritePermission(Build.VERSION.SDK_INT) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
            save()
        } else {
            pending = action
            permissions.launch(if (action == AvatarMediaAction.UPLOAD) AvatarMediaPolicy.readPermissions(Build.VERSION.SDK_INT)
                else arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    fun begin(action: AvatarMediaAction) {
        if (!usable() || saving || pending != null) return
        status = null
        if (action == AvatarMediaAction.SAVE) savingBitmap = bitmap
        val key = if (action == AvatarMediaAction.UPLOAD) "upload_explained_v1" else "album_write_explained_v1"
        val granted = if (action == AvatarMediaAction.UPLOAD) hasReadPermission() else
            !AvatarMediaPolicy.needsWritePermission(Build.VERSION.SDK_INT) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!preferences.getBoolean(key, false) || !granted) explanation = action else proceed(action)
    }

    OutlinedButton(onClick = { begin(AvatarMediaAction.UPLOAD) }, enabled = enabled && !saving && pending == null) {
        Text("选择图片并上传头像")
    }
    OutlinedButton(onClick = { begin(AvatarMediaAction.SAVE) }, enabled = enabled && bitmap != null && !saving && pending == null) {
        Text(if (saving) "正在保存…" else "保存头像到相册")
    }
    status?.let { Text(it) }

    explanation?.let { action ->
        fun cancel() {
            explanation = null
            savingBitmap = null
            report(if (action == AvatarMediaAction.UPLOAD) "已取消授权，未读取或上传图片。" else "已取消授权，未保存图片。")
        }
        AlertDialog(
            onDismissRequest = ::cancel,
            title = { Text(if (action == AvatarMediaAction.UPLOAD) "照片读取与头像上传授权" else "保存图片到相册授权") },
            text = { Text(if (action == AvatarMediaAction.UPLOAD)
                "店时信需要照片读取权限，用于选择您指定的图片并上传到账号服务器作为头像。仅处理您主动选择的图片，不扫描或上传其他照片。Android 14 及以上可仅允许选中的照片。您也可以取消，不影响其他功能。"
                else "经您同意，店时信将当前头像保存到系统相册的“店时信”目录，便于您查看和分享。Android 9 及以下需授予存储写入权限；Android 10 及以上保存本应用图片无需该旧权限。不会读取、修改或删除其他照片；取消不会保存，也不影响当前头像。") },
            confirmButton = {
                TextButton(onClick = {
                    explanation = null
                    val key = if (action == AvatarMediaAction.UPLOAD) "upload_explained_v1" else "album_write_explained_v1"
                    if (!preferences.edit().putBoolean(key, true).commit()) {
                        savingBitmap = null
                        report("无法记录本次授权，请重试。")
                    } else proceed(action)
                }) { Text("同意并继续") }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text("取消") } },
        )
    }

    if (settingsNeeded) {
        AlertDialog(
            onDismissRequest = { settingsNeeded = false },
            title = { Text("权限未开启") },
            text = { Text("未进行图片操作。可在系统设置中开启照片或旧系统的存储写入权限，再返回头像页面重试；也可以暂不开启。") },
            confirmButton = {
                TextButton(onClick = {
                    settingsNeeded = false
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } catch (error: ActivityNotFoundException) {
                        report("无法打开系统设置，请手动进入应用权限设置。", error)
                    }
                }) { Text("前往设置") }
            },
            dismissButton = { TextButton(onClick = { settingsNeeded = false }) { Text("暂不开启") } },
        )
    }
}
