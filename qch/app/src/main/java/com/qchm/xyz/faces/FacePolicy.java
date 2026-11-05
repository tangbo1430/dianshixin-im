package com.qchm.xyz.faces;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FacePolicy {
    private static final String VERSION_3 = "CHAOXXMAI_V3_PROPERTY_A_ENABLED";
    private final boolean propertyAEnabled;

    private FacePolicy(boolean propertyAEnabled) {
        this.propertyAEnabled = propertyAEnabled;
    }

    public boolean useA() {
        return propertyAEnabled;
    }

    public static FacePolicy parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            String trimmed = text.trim();
            Map<String, String> values = new LinkedHashMap<>();
            if (trimmed.startsWith("{")) {
                JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive()) {
                        values.put(entry.getKey(), value.getAsString());
                    }
                }
            } else {
                for (String line : trimmed.split("\\r?\\n")) {
                    int separator = line.indexOf(':');
                    if (separator < 0) continue;
                    values.put(line.substring(0, separator).trim().toUpperCase(Locale.ROOT),
                            line.substring(separator + 1).trim());
                }
                if (values.isEmpty()) return null;
            }
            Boolean version3 = booleanValue(values.get(VERSION_3));
            if (version3 == null) return null;
            return new FacePolicy(version3);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Boolean booleanValue(String value) {
        if (value == null) return null;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1": case "true": case "yes": case "on": return true;
            case "0": case "false": case "no": case "off": return false;
            default: return null;
        }
    }
}