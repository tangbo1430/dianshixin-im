package com.qchm.xyz.bj;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lzy.okgo.OkGo;
import com.qchm.xyz.R;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Intercepts registration HTTP only; never creates accounts or replaces an existing IM session. */
public class RegistrationContinuationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void accountRegistrationAutomaticallyLogsInAndRetryDoesNotRegisterAgain() {
        checkContinuation(RegistActivity2.class, false);
    }

    @Test public void phoneRegistrationAutomaticallyLogsInAndRetryDoesNotRegisterAgain() {
        checkContinuation(RegistActivity.class, false);
    }

    @Test public void malformedLoginResponseKeepsRegisteredAccountForRetry() {
        checkContinuation(RegistActivity2.class, true);
    }

    @Test public void chineseChatBrandIsDianshixin() {
        android.content.res.Configuration config = new android.content.res.Configuration(
                instrumentation.getTargetContext().getResources().getConfiguration());
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        android.content.Context context = instrumentation.getTargetContext().createConfigurationContext(config);
        assertEquals("店时信", context.getString(R.string.conversation_title));
        assertEquals("店时信(未连接)", context.getString(R.string.conversation_title_disconnected));
        assertEquals("关于店时信", context.getString(R.string.about_im));
        assertEquals("店时信 APP", context.getString(R.string.im_statement_content));
    }

    private void checkContinuation(Class<? extends Activity> type, boolean malformed) {
        assumeTrue("Requires explicit device opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("registrationUi")));
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger legacyLogins = new AtomicInteger();
        OkHttpClient previous = OkGo.getInstance().getOkHttpClient();
        OkHttpClient intercepted = new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            String body;
            if (path.endsWith("/create_captcha")) {
                body = "{\"code\":200,\"data\":{\"image\":\"data:image/gif;base64,"
                        + "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7\","
                        + "\"key\":\"local-captcha\"}}";
            } else if (path.endsWith("/user_reg_account") || path.endsWith("/user_reg")) {
                registrations.incrementAndGet();
                body = "{\"code\":200,\"data\":{}}";
            } else if (path.endsWith("/user_login_account")) {
                logins.incrementAndGet();
                body = malformed ? "{\"code\":200,\"data\":{}}"
                        : "{\"code\":503,\"msg\":\"temporarily unavailable\"}";
            } else {
                legacyLogins.incrementAndGet();
                body = "{\"code\":404}";
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
        }).build();
        Instrumentation.ActivityMonitor login = instrumentation.addMonitor(
                LoginActivity.class.getName(), null, false);
        Activity activity = null;
        try {
            OkGo.getInstance().setOkHttpClient(intercepted);
            activity = instrumentation.startActivitySync(new Intent(
                    instrumentation.getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity screen = activity;
            instrumentation.runOnMainSync(() -> {
                ((EditText) screen.findViewById(R.id.etPhone)).setText(
                        type == RegistActivity.class ? "13800000000" : "localaccount");
                ((EditText) screen.findViewById(R.id.etPass)).setText("LocalPass123");
                ((EditText) screen.findViewById(type == RegistActivity.class
                        ? R.id.etPass2 : R.id.etPass1)).setText("LocalPass123");
                ((EditText) screen.findViewById(R.id.etCode)).setText("1234");
                ((CheckBox) screen.findViewById(R.id.iv_check)).setChecked(true);
            });
            if (type == RegistActivity2.class) {
                long deadline = SystemClock.uptimeMillis() + 5000;
                boolean[] ready = {false};
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync(() -> ready[0] =
                            ((RegistActivity2) screen).imCode.getDrawable() != null);
                    if (ready[0]) break;
                    SystemClock.sleep(50);
                }
                assertTrue("Normal captcha callback must complete", ready[0]);
            }
            instrumentation.runOnMainSync(() -> screen.findViewById(R.id.tvLogin).performClick());
            waitRetry(screen, logins, 1);
            assertEquals(1, registrations.get());
            assertEquals(0, legacyLogins.get());
            assertNull("Registration must not redirect to manual login", login.getLastActivity());
            instrumentation.runOnMainSync(() -> screen.findViewById(R.id.tvLogin).performClick());
            waitRetry(screen, logins, 2);
            assertEquals("Retry must not create the account again", 1, registrations.get());
            assertEquals(0, legacyLogins.get());
            assertNull(login.getLastActivity());
        } finally {
            if (activity != null) {
                Activity screen = activity;
                instrumentation.runOnMainSync(screen::finish);
            }
            instrumentation.removeMonitor(login);
            intercepted.dispatcher().cancelAll();
            OkGo.getInstance().setOkHttpClient(previous);
        }
    }

    private void waitRetry(Activity screen, AtomicInteger logins, int expected) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                TextView button = screen.findViewById(R.id.tvLogin);
                ready[0] = button.isEnabled() && "重试进入".contentEquals(button.getText());
            });
            if (logins.get() == expected && ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Registered account must automatically attempt login and offer retry on failure");
    }
}
