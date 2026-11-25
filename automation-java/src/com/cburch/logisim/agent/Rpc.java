// SPDX-License-Identifier: GPL-3.0-only
package com.cburch.logisim.agent;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

final class Rpc {
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static final int INVALID = -32602;
    static final int MISSING = -32001;
    static final int CONFLICT = -32002;
    static final int FORMAT = -32003;
    static final int IO = -32004;
    static final int READ_ONLY = -32005;
    static final int LIMIT = -32006;

    static final class Fault extends RuntimeException {
        final int code;
        final Object details;

        Fault(int code, String message, Object details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    static Fault fault(int code, String message, Object... details) {
        return new Fault(code, message, object(details));
    }

    static Map<String, Object> object(Object... items) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) result.put((String) items[i], items[i + 1]);
        return result;
    }

    static String text(JsonObject params, String key) {
        if (!params.has(key)) throw fault(INVALID, "Required parameter is missing", "parameter", key);
        return text(params, key, null);
    }

    static String text(JsonObject params, String key, String fallback) {
        if (!params.has(key)) return fallback;
        JsonElement value = params.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw fault(INVALID, "Expected a string", "parameter", key);
        }
        return value.getAsString();
    }

    static int integer(JsonObject params, String key, int min, int max) {
        return integer(params, key, min, max, null);
    }

    static int integer(JsonObject params, String key, int min, int max, Integer fallback) {
        JsonElement value = params.get(key);
        if (value == null && fallback != null) return fallback;
        try {
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int number = new BigDecimal(value.getAsString()).intValueExact();
                if (number >= min && number <= max) return number;
            }
        } catch (ArithmeticException | NumberFormatException error) {
            throw fault(INVALID, "Expected an integer", "parameter", key, "value", value);
        }
        throw fault(INVALID, "Integer parameter is out of range or missing", "parameter", key, "min", min, "max", max);
    }

    static boolean bool(JsonObject params, String key, boolean fallback) {
        if (!params.has(key)) return fallback;
        JsonElement value = params.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw fault(INVALID, "Expected a boolean", "parameter", key);
        }
        return value.getAsBoolean();
    }

    static JsonObject fields(JsonObject params, String key) {
        return params.has(key) ? fields(params.get(key), key) : new JsonObject();
    }

    static JsonObject fields(JsonElement value, String key) {
        if (!value.isJsonObject()) throw fault(INVALID, "Expected an object", "parameter", key);
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject params, String key) {
        JsonElement value = params.get(key);
        if (value == null || !value.isJsonArray()) throw fault(INVALID, "Expected an array", "parameter", key);
        return value.getAsJsonArray();
    }

    private Rpc() { }
}
