package com.qchm.xyz.imaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

public class ImSessionRepositoryTest {
    private MockWebServer server;
    private ImSessionRepository repository;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        repository = new ImSessionRepository(new OkHttpClient(), new Gson(), server.url("/").toString());
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void issuesSessionAndUsesLoginToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(
                "{\"code\":200,\"msg\":\"ok\",\"data\":{" +
                        "\"sdk_app_id\":\"20044723\",\"im_user_id\":\"66\"," +
                        "\"user_sig\":\"sig\",\"expires_at\":2000000000}}"));

        AtomicReference<ImSession> result = new AtomicReference<>();
        AtomicReference<ImSessionException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        repository.issue("business-token", callback(result, error, latch));

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(20044723, result.get().getSdkAppId());
        assertEquals("66", result.get().getImUserId());
        assertEquals(null, error.get());
        RecordedRequest request = server.takeRequest();
        assertEquals("/imaccess/session", request.getPath());
        assertEquals("login_token=business-token", request.getBody().readUtf8());
    }

    @Test
    public void reportsAuthenticationFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":401,\"msg\":\"登录失效\",\"data\":null}"));
        AtomicReference<ImSession> result = new AtomicReference<>();
        AtomicReference<ImSessionException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        repository.issue("expired", callback(result, error, latch));

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(null, result.get());
        assertTrue(error.get().isAuthenticationFailure());
        assertEquals(401, error.get().getCode());
    }

    @Test
    public void rejectsMalformedSuccessResponse() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":200,\"data\":{\"sdk_app_id\":\"0\"}}"));
        AtomicReference<ImSessionException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        repository.issue("token", callback(new AtomicReference<>(), error, latch));

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(502, error.get().getCode());
        assertFalse(error.get().isAuthenticationFailure());
    }

    @Test
    public void reportsNetworkFailureWithoutExposingTransportDetails() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        AtomicReference<ImSessionException> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        repository.issue("token", callback(new AtomicReference<>(), error, latch));

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(502, error.get().getCode());
        assertEquals("IM会话服务连接失败", error.get().getMessage());
    }

    private static ImSessionProvider.Callback callback(AtomicReference<ImSession> result,
                                                       AtomicReference<ImSessionException> error,
                                                       CountDownLatch latch) {
        return new ImSessionProvider.Callback() {
            @Override
            public void onSuccess(ImSession session) {
                result.set(session);
                latch.countDown();
            }

            @Override
            public void onError(ImSessionException exception) {
                error.set(exception);
                latch.countDown();
            }
        };
    }
}
