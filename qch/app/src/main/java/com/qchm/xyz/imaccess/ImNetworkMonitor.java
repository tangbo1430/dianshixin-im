package com.qchm.xyz.imaccess;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.qchm.xyz.TIMAppService;

/** Reconnects an already-open local IM session when Android reports network availability. */
public final class ImNetworkMonitor {
    private static final ImNetworkMonitor INSTANCE = new ImNetworkMonitor();
    private static final long RECOVERY_RETRY_DELAY_MS = 15_000L;

    private boolean started;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImNetworkMonitor() {}

    public static ImNetworkMonitor getInstance() {
        return INSTANCE;
    }

    public synchronized void start(Context context) {
        if (started || context == null) {
            return;
        }

        Context applicationContext = context.getApplicationContext();
        ConnectivityManager connectivityManager =
                (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    requestRecovery(applicationContext);
                }

                @Override
                public void onLost(Network network) {
                    if (connectivityManager.getActiveNetwork() == null) {
                        mainHandler.removeCallbacks(recoveryRetry);
                        ImLoginCoordinator.getInstance().onNetworkUnavailable();
                    }
                }
            });
            started = true;
        } catch (RuntimeException ignored) {
            // The immediate reconnect after local login remains available if registration is denied.
        }
    }

    private final Runnable recoveryRetry = () ->
            ImLoginCoordinator.getInstance().connectOnline(TIMAppService.getAppContext());

    /** Reconnect after an SDK transport failure even when Android does not emit a network event. */
    public void requestRecovery(Context context) {
        if (context == null) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        ImLoginCoordinator.getInstance().onImConnectionInterrupted();
        ImLoginCoordinator.getInstance().connectOnline(applicationContext);
        mainHandler.removeCallbacks(recoveryRetry);
        mainHandler.postDelayed(recoveryRetry, RECOVERY_RETRY_DELAY_MS);
    }
}
