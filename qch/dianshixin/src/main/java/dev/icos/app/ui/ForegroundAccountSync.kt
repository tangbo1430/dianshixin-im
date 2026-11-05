package dev.icos.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.icos.app.data.remote.DeviceIdentity

/** The business composition exists only after the activity's agreement and unlock gates. */
@Composable
internal fun ForegroundAccountSync(
    model: FinanceViewModel,
    enabled: Boolean,
    identity: DeviceIdentity?,
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(model, context, lifecycleOwner, enabled, identity) {
        var stopListening: (() -> Unit)? = null
        fun stop() {
            stopListening?.invoke()
            stopListening = null
            model.setAutomaticSyncActive(false)
        }
        fun start() {
            if (!enabled || identity == null || stopListening != null) return
            model.setAutomaticSyncActive(true)
            stopListening = listenForValidatedInternet(context) {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    model.requestAutomaticSync()
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
}

/** API 24 default-network callbacks run on the connectivity thread; all state stays on main. */
private fun listenForValidatedInternet(context: Context, onConnected: () -> Unit): () -> Unit {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val main = Handler(Looper.getMainLooper())
    var listening = true
    var currentNetwork = connectivity.activeNetwork
    var validated = connectivity.getNetworkCapabilities(currentNetwork).hasValidatedInternet()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val connected = capabilities.hasValidatedInternet()
            main.post {
                if (listening) {
                    val restored = connected && (!validated || currentNetwork != network)
                    currentNetwork = network
                    validated = connected
                    if (restored) onConnected()
                }
            }
        }

        override fun onLost(network: Network) {
            main.post {
                if (listening && currentNetwork == network) {
                    currentNetwork = null
                    validated = false
                }
            }
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    if (validated) onConnected()
    return {
        listening = false
        connectivity.unregisterNetworkCallback(callback)
        main.removeCallbacksAndMessages(null)
    }
}

private fun NetworkCapabilities?.hasValidatedInternet(): Boolean =
    this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
