package com.qchm.xyz.faces;

import com.qchm.xyz.SplashActivity;
import dev.icos.app.MainActivity;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FaceTargetTest {
    @Test public void initialOrInvalidPolicySelectsStoreWithoutImFallback() {
        assertEquals(MainActivity.class, FaceEntryActivity.targetClass(null));
        assertEquals(MainActivity.class, FaceEntryActivity.targetClass(FacePolicy.parse("{}")));
    }

    @Test public void universalCosValueSelectsExactIncludedActivity() {
        assertEquals(MainActivity.class, FaceEntryActivity.targetClass(FacePolicy.parse("CHAOXXMAI_V3_PROPERTY_A_ENABLED:1")));
        assertEquals(SplashActivity.class, FaceEntryActivity.targetClass(FacePolicy.parse("CHAOXXMAI_V3_PROPERTY_A_ENABLED:0")));
    }
}
