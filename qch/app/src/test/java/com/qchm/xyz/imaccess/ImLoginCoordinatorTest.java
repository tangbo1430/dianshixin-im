package com.qchm.xyz.imaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.tencent.imsdk.BaseConstants;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ImLoginCoordinatorTest {
    @Test
    public void mergesConcurrentLoginRequests() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        ImLoginCoordinator coordinator = coordinator(provider, gateway, store);
        CountingCallback first = new CountingCallback();
        CountingCallback second = new CountingCallback();

        coordinator.loginWithToken(null, "token", first);
        coordinator.loginWithToken(null, "token", second);
        assertEquals(1, provider.calls);

        provider.succeed(session());
        gateway.succeed();

        assertEquals(1, first.successes);
        assertEquals(1, second.successes);
        assertTrue(store.autoLogin);
    }

    @Test
    public void refreshesUserSigOnlyOnce() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        CountingCallback callback = new CountingCallback();
        ImLoginCoordinator coordinator = coordinator(provider, gateway, store);

        coordinator.loginWithToken(null, "token", callback);
        provider.succeed(session());
        gateway.fail(BaseConstants.ERR_USER_SIG_EXPIRED);
        assertEquals(2, provider.calls);

        provider.succeed(session());
        gateway.fail(BaseConstants.ERR_USER_SIG_EXPIRED);

        assertEquals(2, provider.calls);
        assertEquals(1, callback.errors);
    }

    @Test
    public void usesFreshCachedSessionForAutoLogin() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        store.token = "token";
        store.autoLogin = true;
        store.session = new ImSession(20044723, "66", "sig", System.currentTimeMillis() / 1000 + 3600);
        CountingCallback callback = new CountingCallback();

        coordinator(provider, gateway, store).autoLogin(null, callback);
        gateway.succeed();

        assertEquals(0, provider.calls);
        assertEquals(1, callback.successes);
    }

    @Test
    public void clearsSessionWhenBusinessTokenIsRejected() {
        FakeProvider provider = new FakeProvider();
        FakeStore store = new FakeStore();
        CountingCallback callback = new CountingCallback();

        coordinator(provider, new FakeGateway(), store).loginWithToken(null, "token", callback);
        provider.fail(new ImSessionException(401, "登录失效", true));

        assertTrue(store.cleared);
        assertEquals(1, callback.errors);
    }

    @Test
    public void refreshPolicyUsesFifteenMinuteWindow() {
        long now = 1000000;
        assertTrue(ImSessionPolicy.isUsable(new ImSession(1, "u", "s", now + 901), now));
        assertFalse(ImSessionPolicy.isUsable(new ImSession(1, "u", "s", now + 900), now));
    }

    @Test
    public void localAutoLoginOpensCacheBeforeOnlineReconnect() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        store.token = "token";
        store.autoLogin = true;
        store.session = session();
        store.lastOnlineAuthAt = System.currentTimeMillis() / 1000;
        CountingCallback callback = new CountingCallback();

        ImLoginCoordinator coordinator = coordinator(provider, gateway, store);
        coordinator.autoLogin(null, callback);
        gateway.localSucceed();

        assertEquals(1, callback.successes);
        assertEquals(1, provider.calls);
        assertEquals(ImLoginCoordinator.State.CONNECTING, coordinator.getState());

        provider.succeed(session());
        gateway.succeed();

        assertEquals(ImLoginCoordinator.State.ONLINE, coordinator.getState());
        assertTrue(store.lastOnlineAuthAt > 0);
    }

    @Test
    public void localAccessExpiresAfterSevenDays() {
        long now = 1000000;
        assertTrue(ImSessionPolicy.canOpenLocal(new ImSession(1, "u", "s", 1),
                now - ImSessionPolicy.OFFLINE_GRACE_SECONDS, now));
        assertFalse(ImSessionPolicy.canOpenLocal(new ImSession(1, "u", "s", 1),
                now - ImSessionPolicy.OFFLINE_GRACE_SECONDS - 1, now));
    }

    @Test
    public void userSigRefreshNetworkFailureKeepsLocalMode() {
        FakeProvider provider = new FakeProvider();
        FakeStore store = new FakeStore();
        store.token = "token";
        store.session = session();
        store.autoLogin = true;
        CountingCallback callback = new CountingCallback();
        ImLoginCoordinator coordinator = coordinator(provider, new FakeGateway(), store);

        coordinator.refreshAfterExpiry(null, callback);
        provider.fail(new ImSessionException(502, "network error", false));

        assertEquals(ImLoginCoordinator.State.LOCAL_ONLY, coordinator.getState());
        assertEquals(1, callback.errors);
        assertFalse(store.cleared);
        assertTrue(store.autoLogin);
    }

    @Test
    public void userSigRefreshAuthenticationFailureExpiresSession() {
        FakeProvider provider = new FakeProvider();
        FakeStore store = new FakeStore();
        store.token = "token";
        store.session = session();
        store.autoLogin = true;
        CountingCallback callback = new CountingCallback();
        ImLoginCoordinator coordinator = coordinator(provider, new FakeGateway(), store);

        coordinator.refreshAfterExpiry(null, callback);
        provider.fail(new ImSessionException(401, "expired", true));

        assertEquals(ImLoginCoordinator.State.AUTH_EXPIRED, coordinator.getState());
        assertEquals(1, callback.errors);
        assertTrue(store.cleared);
    }

    @Test
    public void networkAvailabilityOnlyReconnectsLocalSession() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        store.token = "token";
        ImLoginCoordinator coordinator = coordinator(provider, gateway, store);

        coordinator.connectOnline(null);
        assertEquals(0, provider.calls);

        store.autoLogin = true;
        store.session = session();
        store.lastOnlineAuthAt = System.currentTimeMillis() / 1000;
        coordinator.autoLogin(null, new CountingCallback());
        gateway.localSucceed();
        assertEquals(1, provider.calls);
        assertEquals(ImLoginCoordinator.State.CONNECTING, coordinator.getState());
    }

    @Test
    public void sdkTransportFailureKeepsSessionAndReconnects() {
        FakeProvider provider = new FakeProvider();
        FakeGateway gateway = new FakeGateway();
        FakeStore store = new FakeStore();
        store.token = "token";
        store.autoLogin = true;
        store.session = session();
        store.lastOnlineAuthAt = System.currentTimeMillis() / 1000;
        ImLoginCoordinator coordinator = coordinator(provider, gateway, store);

        coordinator.autoLogin(null, new CountingCallback());
        gateway.localSucceed();
        provider.succeed(session());
        gateway.succeed();
        assertEquals(ImLoginCoordinator.State.ONLINE, coordinator.getState());

        coordinator.onImConnectionInterrupted();
        assertEquals(ImLoginCoordinator.State.LOCAL_ONLY, coordinator.getState());
        assertFalse(store.cleared);
        assertEquals("token", store.token);

        coordinator.connectOnline(null);
        assertEquals(2, provider.calls);
        provider.succeed(session());
        gateway.succeed();

        assertEquals(ImLoginCoordinator.State.ONLINE, coordinator.getState());
        assertFalse(store.cleared);
    }

    private static ImLoginCoordinator coordinator(FakeProvider provider, FakeGateway gateway, FakeStore store) {
        return new ImLoginCoordinator(provider, gateway, store, Runnable::run);
    }

    private static ImSession session() {
        return new ImSession(20044723, "66", "sig", 2000000000);
    }

    private static class FakeProvider implements ImSessionProvider {
        int calls;
        Callback callback;

        @Override
        public void issue(String loginToken, Callback callback) {
            calls++;
            this.callback = callback;
        }

        void succeed(ImSession session) {
            Callback pending = callback;
            callback = null;
            pending.onSuccess(session);
        }

        void fail(ImSessionException error) {
            callback.onError(error);
        }
    }

    private static class FakeGateway implements ImLoginGateway {
        final List<Callback> callbacks = new ArrayList<>();
        final List<Callback> localCallbacks = new ArrayList<>();

        @Override
        public void login(Context context, ImSession session, Callback callback) {
            callbacks.add(callback);
        }

        @Override
        public void openLocal(Context context, ImSession session, Callback callback) {
            localCallbacks.add(callback);
        }

        void succeed() {
            callbacks.remove(0).onSuccess();
        }

        void fail(int code) {
            callbacks.remove(0).onError(code, "login failed");
        }

        void localSucceed() {
            localCallbacks.remove(0).onSuccess();
        }
    }

    private static class FakeStore implements ImSessionStore {
        String token = "";
        ImSession session = new ImSession(0, "", "", 0);
        boolean autoLogin;
        boolean cleared;
        long lastOnlineAuthAt;

        @Override public String getLoginToken() { return token; }
        @Override public ImSession getSession() { return session; }
        @Override public boolean isAutoLogin() { return autoLogin; }
        @Override public long getLastOnlineAuthAt() { return lastOnlineAuthAt; }
        @Override public void saveLoginToken(String value) { token = value; }
        @Override public void saveSession(ImSession value) { session = value; }
        @Override public void setAutoLogin(boolean value) { autoLogin = value; }
        @Override public void setLastOnlineAuthAt(long value) { lastOnlineAuthAt = value; }
        @Override public void clear() { cleared = true; token = ""; autoLogin = false; }
    }

    private static class CountingCallback implements ImLoginCoordinator.Callback {
        int successes;
        int errors;

        @Override public void onSuccess(ImSession session) { successes++; }
        @Override public void onError(ImSessionException error) { errors++; }
    }
}
