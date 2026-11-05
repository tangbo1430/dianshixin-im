package com.qchm.xyz.faces;

import org.junit.Test;
import static org.junit.Assert.*;

public class FacePolicyTest {
    @Test public void version3TrueSelectsARegardlessOfOldFlags() {
        FacePolicy policy = FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":true,"
                + "\"CHAOXXMAI_PROPERTY_A_ENABLED\":false,"
                + "\"CHAOXXMAI_VIVO_PROPERTY_A_ENABLED\":false,"
                + "\"CHAOXXMAI_HUAWEI_PROPERTY_A_ENABLED\":false}");
        assertTrue(policy.useA());
    }

    @Test public void version3FalseSelectsBRegardlessOfOldFlags() {
        FacePolicy policy = FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":false,"
                + "\"CHAOXXMAI_PROPERTY_A_ENABLED\":true,"
                + "\"CHAOXXMAI_VIVO_PROPERTY_A_ENABLED\":true,"
                + "\"CHAOXXMAI_HUAWEI_PROPERTY_A_ENABLED\":true}");
        assertFalse(policy.useA());
    }

    @Test public void missingAndInvalidConfigurationCannotSelectB() {
        assertNull(FacePolicy.parse(""));
        assertNull(FacePolicy.parse("{bad"));
        assertNull(FacePolicy.parse("<html>error</html>"));
        assertNull(FacePolicy.parse("{}"));
        assertNull(FacePolicy.parse("{\"888888\":{\"SERVER_IP\":\"example.com\"}}"));
        assertNull(FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":\"invalid\"}"));
    }

    @Test public void missingVersion3FlagDoesNotUseOldFlags() {
        FacePolicy policy = FacePolicy.parse("{\"CHAOXXMAI_PROPERTY_A_ENABLED\":true,"
                + "\"CHAOXXMAI_VIVO_PROPERTY_A_ENABLED\":true,"
                + "\"CHAOXXMAI_HUAWEI_PROPERTY_A_ENABLED\":true}");
        assertNull(policy);
        assertNull(FacePolicy.parse("CHAOXXMAI_PROPERTY_A_ENABLED:yes"));
    }

    @Test public void legacyTextAndJsonSwitchOnlyAreSupported() {
        assertTrue(FacePolicy.parse("CHAOXXMAI_V3_PROPERTY_A_ENABLED:ON\nSERVER_IP:https://example.com").useA());
        assertTrue(FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":1}").useA());
        assertFalse(FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":0}").useA());
        assertNull(FacePolicy.parse("CHAOXXMAI_V3_PROPERTY_A_ENABLED:invalid"));
        assertNull(FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":2}"));
        assertNull(FacePolicy.parse("{\"CHAOXXMAI_V3_PROPERTY_A_ENABLED\":1.5}"));
    }
}