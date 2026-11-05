package com.qchm.xyz.imaccess;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tencent.qcloud.tuikit.tuichat.model.MessageSendDispatcher;

import java.util.ArrayList;
import java.util.List;

/** Coordinates business authentication and Tencent IM login without owning a message store. */
public class ImLoginCoordinator {
    private static final String TAG = "ImLoginCoordinator";
    public enum State {
        IDLE,
        CONNECTING,
        LOCAL_ONLY,
        ONLINE,
        AUTH_EXPIRED
    }

    public interface Callback {
        void onSuccess(ImSession session);

        void onError(ImSessionException error);
    }

    public interface StateListener {
        void onStateChanged(State state);
    }

    interface Dispatcher {
        void dispatch(Runnable runnable);
    }

    private static final ImLoginCoordinator INSTANCE = new ImLoginCoordinator(
            new ImSessionRepository(),
            new TencentImLoginGateway(),
            new UserInfoSessionStore(),
            runnable -> new Handler(Looper.getMainLooper()).post(runnable)
    );

    private final ImSessionProvider sessionProvider;
    private final ImLoginGateway loginGateway;
    private final ImSessionStore sessionStore;
    private final Dispatcher dispatcher;
    private final List<Callback> callbacks = new ArrayList<>();
    private final List<StateListener> stateListeners = new ArrayList<>();
    private boolean inFlight;
    private boolean reconnectInFlight;
    private boolean fallbackToLocalOnError;
    private String activeToken = "";
    private State state = State.IDLE;

    ImLoginCoordinator(ImSessionProvider sessionProvider, ImLoginGateway loginGateway,
                       ImSessionStore sessionStore, Dispatcher dispatcher) {
        this.sessionProvider = sessionProvider;
        this.loginGateway = loginGateway;
        this.sessionStore = sessionStore;
        this.dispatcher = dispatcher;
    }

    public static ImLoginCoordinator getInstance() {
        return INSTANCE;
    }

    public synchronized State getState() {
        return state;
    }

    public boolean canAutoOpenLocal() {
        if (!sessionStore.isAutoLogin()) {
            return false;
        }
        String token = sessionStore.getLoginToken();
        return !isBlank(token)
                && sessionStore.isSessionForToken(token)
                && ImSessionPolicy.canOpenLocal(sessionStore.getSession(),
                sessionStore.getLastOnlineAuthAt(), nowSeconds());
    }

    public synchronized void addStateListener(StateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public synchronized void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public void loginWithToken(Context context, String loginToken, Callback callback) {
        startOnline(context, loginToken, true, callback);
    }

    public void autoLogin(Context context, Callback callback) {
        if (!sessionStore.isAutoLogin()) {
            callback.onError(new ImSessionException(401, "请重新登录", true));
            return;
        }

        String token = sessionStore.getLoginToken();
        ImSession cached = sessionStore.getSession();
        if (isBlank(token)) {
            MessageSendDispatcher.deactivateAccount();
            sessionStore.clear();
            callback.onError(new ImSessionException(401, "登录已失效", true));
            return;
        }

        if (sessionStore.isSessionForToken(token)
                && ImSessionPolicy.canOpenLocal(cached, sessionStore.getLastOnlineAuthAt(), nowSeconds())) {
            startLocal(context, token.trim(), cached, callback);
        } else {
            startOnline(context, token, false, callback);
        }
    }

    public void refreshAfterExpiry(Context context, Callback callback) {
        startOnline(context, sessionStore.getLoginToken(), true, true, callback);
    }

    /** Starts one online re-authentication after local-only mode or network recovery. */
    public void connectOnline(Context context) {
        String token = sessionStore.getLoginToken();
        if (isBlank(token)) {
            return;
        }

        synchronized (this) {
            if (inFlight || reconnectInFlight || state != State.LOCAL_ONLY) {
                return;
            }
            reconnectInFlight = true;
        }
        setState(State.CONNECTING);
        issueOnlineInBackground(context, token.trim(), false);
    }

    /** Keeps the local database available while marking the online channel unavailable. */
    public void onNetworkUnavailable() {
        synchronized (this) {
            if (state != State.ONLINE) {
                return;
            }
        }
        setState(State.LOCAL_ONLY);
    }

    /**
     * The Tencent SDK can report a transport failure while the Android network remains available.
     * Keep the business session and local message database intact, then allow the monitor to
     * obtain a fresh UserSig and reconnect. Only an explicit 401/403 may clear the session.
     */
    public void onImConnectionInterrupted() {
        synchronized (this) {
            if (state == State.IDLE || state == State.AUTH_EXPIRED || isBlank(sessionStore.getLoginToken())
                    || !sessionStore.isAutoLogin()) {
                return;
            }
        }
        setState(State.LOCAL_ONLY);
    }

    /** Clears coordinator state when the business account explicitly logs out. */
    public void clearSessionState() {
        synchronized (this) {
            inFlight = false;
            reconnectInFlight = false;
            callbacks.clear();
            activeToken = "";
            fallbackToLocalOnError = false;
        }
        MessageSendDispatcher.deactivateAccount();
        sessionStore.clear();
        setState(State.IDLE);
    }

    private void startLocal(Context context, String token, ImSession cached, Callback callback) {
        synchronized (this) {
            if (inFlight) {
                if (activeToken.equals(token)) {
                    callbacks.add(callback);
                } else {
                    callback.onError(new ImSessionException(409, "正在登录其他账号", false));
                }
                return;
            }
            inFlight = true;
            activeToken = token;
            callbacks.add(callback);
        }

        sessionStore.saveLoginToken(token);
        setState(State.CONNECTING);
        loginGateway.openLocal(context == null ? null : context.getApplicationContext(), cached,
                new ImLoginGateway.Callback() {
                    @Override
                    public void onSuccess() {
                        if (!isActiveInteractive(token)) {
                            return;
                        }
                        MessageSendDispatcher.activateAccount(cached.getImUserId());
                        sessionStore.setAutoLogin(true);
                        setState(State.LOCAL_ONLY);
                        finishSuccess(cached);
                        connectOnline(context);
                    }

                    @Override
                    public void onError(int code, String message) {
                        if (!isActiveInteractive(token)) {
                            return;
                        }
                        // A local database may be unavailable after a damaged app install. Try a normal login.
                        issueOnlineInteractive(context, token, false);
                    }
                });
    }

    private void startOnline(Context context, String token, boolean forceRefresh, Callback callback) {
        startOnline(context, token, forceRefresh, false, callback);
    }

    private void startOnline(Context context, String token, boolean forceRefresh,
                             boolean fallbackToLocal, Callback callback) {
        String normalizedToken = token == null ? "" : token.trim();
        if (normalizedToken.isEmpty()) {
            MessageSendDispatcher.deactivateAccount();
            sessionStore.clear();
            setState(State.AUTH_EXPIRED);
            callback.onError(new ImSessionException(401, "登录已失效", true));
            return;
        }

        synchronized (this) {
            if (inFlight) {
                if (activeToken.equals(normalizedToken)) {
                    callbacks.add(callback);
                } else {
                    callback.onError(new ImSessionException(409, "正在登录其他账号", false));
                }
                return;
            }
            inFlight = true;
            activeToken = normalizedToken;
            fallbackToLocalOnError = fallbackToLocal;
            callbacks.add(callback);
        }

        sessionStore.saveLoginToken(normalizedToken);
        Context applicationContext = context == null ? null : context.getApplicationContext();
        ImSession cached = sessionStore.getSession();
        if (!forceRefresh && sessionStore.isSessionForToken(normalizedToken)
                && ImSessionPolicy.isUsable(cached, nowSeconds())) {
            loginTencentInteractive(applicationContext, cached, normalizedToken, false);
        } else {
            issueOnlineInteractive(applicationContext, normalizedToken, false);
        }
    }

    private void issueOnlineInteractive(Context context, String token, boolean alreadyRetried) {
        setState(State.CONNECTING);
        sessionProvider.issue(token, new ImSessionProvider.Callback() {
            @Override
            public void onSuccess(ImSession session) {
                if (!isActiveInteractive(token)) {
                    return;
                }
                sessionStore.saveSession(session, token);
                Log.i(TAG, "IM session issued for app=" + session.getSdkAppId() + " user=" + session.getImUserId());
                dispatcher.dispatch(() -> loginTencentInteractive(context, session, token, alreadyRetried));
            }

            @Override
            public void onError(ImSessionException error) {
                if (!isActiveInteractive(token)) {
                    return;
                }
                if (error.isAuthenticationFailure()) {
                    MessageSendDispatcher.deactivateAccount();
                    sessionStore.clear();
                    setState(State.AUTH_EXPIRED);
                } else if (getState() == State.LOCAL_ONLY) {
                    setState(State.LOCAL_ONLY);
                }
                finishError(error);
            }
        });
    }

    private void loginTencentInteractive(Context context, ImSession session, String token, boolean alreadyRetried) {
        loginGateway.login(context, session, new ImLoginGateway.Callback() {
            @Override
            public void onSuccess() {
                if (!isActiveInteractive(token)) {
                    return;
                }
                MessageSendDispatcher.activateAccount(session.getImUserId());
                markOnline();
                finishSuccess(session);
            }

            @Override
            public void onError(int code, String message) {
                if (!isActiveInteractive(token)) {
                    return;
                }
                if (!alreadyRetried && ImSessionPolicy.isUserSigError(code)) {
                    issueOnlineInteractive(context, token, true);
                    return;
                }
                finishError(new ImSessionException(code,
                        message == null || message.trim().isEmpty() ? "腾讯IM登录失败" : message, false));
            }
        });
    }

    private void issueOnlineInBackground(Context context, String token, boolean alreadyRetried) {
        sessionProvider.issue(token, new ImSessionProvider.Callback() {
            @Override
            public void onSuccess(ImSession session) {
                if (!isActiveReconnect(token)) {
                    return;
                }
                sessionStore.saveSession(session, token);
                Log.i(TAG, "IM session refreshed for app=" + session.getSdkAppId() + " user=" + session.getImUserId());
                dispatcher.dispatch(() -> loginTencentInBackground(context, session, token, alreadyRetried));
            }

            @Override
            public void onError(ImSessionException error) {
                if (!isActiveReconnect(token)) {
                    return;
                }
                finishReconnectError(error);
            }
        });
    }

    private void loginTencentInBackground(Context context, ImSession session, String token, boolean alreadyRetried) {
        loginGateway.login(context, session, new ImLoginGateway.Callback() {
            @Override
            public void onSuccess() {
                if (!isActiveReconnect(token)) {
                    return;
                }
                MessageSendDispatcher.activateAccount(session.getImUserId());
                markOnline();
                finishReconnect();
            }

            @Override
            public void onError(int code, String message) {
                if (!isActiveReconnect(token)) {
                    return;
                }
                if (!alreadyRetried && ImSessionPolicy.isUserSigError(code)) {
                    issueOnlineInBackground(context, token, true);
                    return;
                }
                finishReconnectError(new ImSessionException(code,
                        message == null || message.trim().isEmpty() ? "腾讯IM登录失败" : message, false));
            }
        });
    }

    private void markOnline() {
        sessionStore.setAutoLogin(true);
        sessionStore.setLastOnlineAuthAt(nowSeconds());
        setState(State.ONLINE);
    }

    private void finishReconnect() {
        synchronized (this) {
            reconnectInFlight = false;
        }
    }

    private void finishReconnectError(ImSessionException error) {
        synchronized (this) {
            reconnectInFlight = false;
        }
        if (error.isAuthenticationFailure()) {
            MessageSendDispatcher.deactivateAccount();
            sessionStore.clear();
            setState(State.AUTH_EXPIRED);
        } else {
            setState(State.LOCAL_ONLY);
        }
    }

    private void finishSuccess(ImSession session) {
        List<Callback> pending = takeCallbacks();
        dispatcher.dispatch(() -> {
            for (Callback callback : pending) {
                callback.onSuccess(session);
            }
        });
    }

    private void finishError(ImSessionException error) {
        if (!error.isAuthenticationFailure() && getState() == State.CONNECTING) {
            setState(shouldFallbackToLocalOnError() ? State.LOCAL_ONLY : State.IDLE);
        }
        List<Callback> pending = takeCallbacks();
        dispatcher.dispatch(() -> {
            for (Callback callback : pending) {
                callback.onError(error);
            }
        });
    }

    private synchronized List<Callback> takeCallbacks() {
        List<Callback> pending = new ArrayList<>(callbacks);
        callbacks.clear();
        inFlight = false;
        activeToken = "";
        fallbackToLocalOnError = false;
        return pending;
    }

    private synchronized boolean shouldFallbackToLocalOnError() {
        return fallbackToLocalOnError;
    }

    private synchronized boolean isActiveInteractive(String token) {
        return inFlight && activeToken.equals(token);
    }

    private synchronized boolean isActiveReconnect(String token) {
        return reconnectInFlight && token.equals(sessionStore.getLoginToken());
    }

    private void setState(State nextState) {
        List<StateListener> listeners;
        synchronized (this) {
            if (state == nextState) {
                return;
            }
            state = nextState;
            listeners = new ArrayList<>(stateListeners);
        }
        Log.i(TAG, "state=" + nextState);
        dispatcher.dispatch(() -> {
            for (StateListener listener : listeners) {
                listener.onStateChanged(nextState);
            }
        });
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }
}
