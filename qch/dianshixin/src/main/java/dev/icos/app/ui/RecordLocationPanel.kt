package dev.icos.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.icos.app.data.local.FinanceRecord
import dev.icos.app.data.local.RecordLocationAttachment
import dev.icos.app.data.local.RecordLocationAttachmentStore
import dev.icos.app.domain.RecordCoordinates
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun RecordLocationPanel(
    record: FinanceRecord?,
    ownerId: UUID,
    epochId: UUID,
    enabled: Boolean,
    operationError: String?,
    refreshKey: Long,
    cleanupFailed: Boolean,
    onSave: (FinanceRecord, String, String, String) -> Unit,
    onDelete: (FinanceRecord) -> Unit,
    onRetryCleanup: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var latitude by remember(ownerId, epochId, record?.id) { mutableStateOf("") }
    var longitude by remember(ownerId, epochId, record?.id) { mutableStateOf("") }
    var label by remember(ownerId, epochId, record?.id) { mutableStateOf("") }
    var status by remember(ownerId, epochId, record?.id) { mutableStateOf("位置仅保存在本机，不会同步到服务器。") }
    var requesting by remember(ownerId, epochId, record?.id) { mutableStateOf(false) }
    var pendingRecord by remember(ownerId, epochId, record?.id) { mutableStateOf<FinanceRecord?>(null) }
    var cancelLocation by remember(ownerId, epochId, record?.id) { mutableStateOf<(() -> Unit)?>(null) }
    val saved by produceState<RecordLocationAttachment?>(
        null,
        record?.id,
        record?.currentEventId,
        record?.localRevision,
        ownerId,
        epochId,
        refreshKey,
    ) {
        value = null
        val current = record ?: return@produceState
        value =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // A stored fix belongs to the event/revision it was saved for; an edited bill must not display it as current.
                RecordLocationAttachmentStore
                    .read(context, ownerId.toString(), epochId.toString(), current.id.toString())
                    ?.takeIf { it.eventId == current.currentEventId?.toString() && it.revision == current.localRevision }
            }
    }
    LaunchedEffect(operationError) { operationError?.let { status = it } }
    LaunchedEffect(saved, record?.id) {
        latitude = saved?.latitude.orEmpty()
        longitude = saved?.longitude.orEmpty()
        label = saved?.label.orEmpty()
    }
    val permissionRequest =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val current = pendingRecord
            pendingRecord = null
            if (current == null || record == null || current.id != record.id || current.currentEventId != record.currentEventId ||
                current.localRevision != record.localRevision
            ) {
                requesting = false
                status = "账单已切换，已取消定位。"
                return@rememberLauncherForActivityResult
            }
            val fine =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            val coarse =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                requesting = false
                status = "未授予定位权限；可手动输入坐标。"
                return@rememberLauncherForActivityResult
            }
            cancelLocation =
                startOneShotLocation(
                    context,
                    fine,
                    current,
                    onLocation = { location ->
                        latitude = coordinateText(location.latitude)
                        longitude = coordinateText(location.longitude)
                        requesting =
                            false
                        status = "已获取一次当前位置；确认后单独保存。"
                    },
                    onStatus = {
                        status = it
                        requesting = it == "正在获取一次当前位置…"
                    },
                    isCurrent = {
                        record?.id == current.id && record.currentEventId == current.currentEventId &&
                            record.localRevision == current.localRevision
                    },
                )
        }
    DisposableEffect(ownerId, epochId, record?.id, record?.currentEventId, record?.localRevision, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && requesting &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cancelLocation?.invoke()
                    cancelLocation = null
                    requesting = false
                    status = "定位权限已撤销；本次采集已停止，可手动输入坐标。"
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelLocation?.invoke()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("位置（本机私有）")
        saved?.let { attachment ->
            Text(
                "${attachment.latitude}, ${attachment.longitude}${attachment.label.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    val labelPart =
                        attachment.label
                            .takeIf(
                                String::isNotBlank,
                            )?.let { "?q=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }
                            .orEmpty()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${attachment.latitude},${attachment.longitude}$labelPart"))
                    if (intent.resolveActivity(context.packageManager) !=
                        null
                    ) {
                        context.startActivity(intent)
                    } else {
                        status = "没有可打开 geo: 位置的应用。"
                    }
                }, enabled = enabled) { Text("在地图应用中打开") }
                TextButton(onClick = { record?.let(onDelete) }, enabled = enabled) { Text("删除位置") }
            }
        }
        if (cleanupFailed) TextButton(onClick = onRetryCleanup, enabled = enabled) { Text("重试清理已删除账单的位置") }
        if (record == null) {
            Text("先保存账单，再为该账单单独添加位置；位置不会影响金额或备注。")
        } else {
            OutlinedTextField(
                latitude,
                { latitude = it },
                label = { Text("纬度 −90 到 90") },
                enabled = enabled && !requesting,
                singleLine = true,
            )
            OutlinedTextField(
                longitude,
                { longitude = it },
                label = { Text("经度 −180 到 180") },
                enabled = enabled && !requesting,
                singleLine = true,
            )
            OutlinedTextField(label, {
                if (it.length <=
                    160
                ) {
                    label = it
                }
            }, label = { Text("位置标签（选填）") }, enabled = enabled && !requesting, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    pendingRecord = record
                    requesting = true
                    status = "正在请求定位权限…"
                    val fine =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                    val coarse =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) {
                        cancelLocation =
                            startOneShotLocation(
                                context,
                                fine,
                                record,
                                onLocation = { location ->
                                    latitude = coordinateText(location.latitude)
                                    longitude =
                                        coordinateText(location.longitude)
                                    requesting = false
                                    status = "已获取一次当前位置；确认后单独保存。"
                                },
                                onStatus = {
                                    status = it
                                    requesting = it == "正在获取一次当前位置…"
                                },
                                isCurrent = {
                                    record.id == pendingRecord?.id && record.currentEventId == pendingRecord?.currentEventId &&
                                        record.localRevision == pendingRecord?.localRevision
                                },
                            )
                    } else {
                        permissionRequest.launch(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                        )
                    }
                }, enabled = enabled && !requesting) { Text(if (requesting) "定位中…" else "获取一次当前位置") }
                if (requesting) {
                    TextButton(onClick = {
                        cancelLocation?.invoke()
                        cancelLocation = null
                        requesting = false
                        status =
                            "已取消定位请求；未保存位置。"
                    }) { Text("取消定位") }
                }
                Button(onClick = {
                    runCatching { RecordCoordinates.validate(latitude, longitude) }
                        .onSuccess {
                            onSave(record, latitude, longitude, label)
                            status = "位置保存请求已提交；账单金额与备注未更改。"
                        }.onFailure { status = it.message ?: "坐标无效" }
                }, enabled = enabled && !requesting) { Text("单独保存位置") }
            }
        }
        Text(status)
    }
}

private fun coordinateText(value: Double): String = value.toString()

private fun startOneShotLocation(
    context: Context,
    fine: Boolean,
    record: FinanceRecord,
    onLocation: (Location) -> Unit,
    onStatus: (String) -> Unit,
    isCurrent: () -> Boolean,
): (() -> Unit)? {
    if (!isCurrent()) {
        onStatus("账单已切换，已取消定位。")
        return null
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers =
        listOfNotNull(if (fine) LocationManager.GPS_PROVIDER else null, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
    if (providers.isEmpty()) {
        onStatus("没有可用定位来源；可手动输入坐标。")
        return null
    }
    val provider = providers.first()
    val permission =
        if (provider == LocationManager.GPS_PROVIDER &&
            fine
        ) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.ACCESS_COARSE_LOCATION
        }
    if (ContextCompat.checkSelfPermission(context, permission) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        onStatus("定位权限已撤销；请重新授权或手动输入坐标。")
        return null
    }
    val finished = AtomicBoolean(false)
    val handler = Handler(Looper.getMainLooper())
    lateinit var listener: LocationListener

    fun finish(
        status: String,
        location: Location? = null,
    ) {
        if (!finished.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(listener)
        runCatching { manager.removeUpdates(listener) }
        if (isCurrent()) {
            if (location != null) onLocation(location) else onStatus(status)
        } else {
            onStatus("账单已切换，已取消定位。")
        }
    }
    listener = LocationListener { location -> finish("", location) }
    try {
        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        onStatus("正在获取一次当前位置…")
        handler.postAtTime({ finish("定位超时；可手动输入坐标。") }, listener, android.os.SystemClock.uptimeMillis() + 15_000)
        manager.getLastKnownLocation(provider)?.let { location -> finish("", location) }
    } catch (_: SecurityException) {
        finish("定位权限已撤销；请重新授权或手动输入坐标。")
    } catch (_: Exception) {
        finish("无法启动定位；可手动输入坐标。")
    }
    return { finish("已取消定位请求；未保存位置。") }
}
