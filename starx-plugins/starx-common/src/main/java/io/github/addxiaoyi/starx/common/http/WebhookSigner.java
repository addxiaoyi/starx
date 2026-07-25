/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.http;

import io.github.addxiaoyi.starx.api.dto.WebhookPayload;
import io.github.addxiaoyi.starx.common.crypto.HmacSigner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;

public final class WebhookSigner {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapter((Type)((Object)Instant.class), new InstantTypeAdapter()).disableHtmlEscaping().create();

    private WebhookSigner() {
    }

    public static Map<String, String> sign(WebhookPayload payload, String secret) {
        String rawBody = WebhookSigner.toJson(payload);
        String timestamp = String.valueOf(payload.timestamp().getEpochSecond());
        String signature = HmacSigner.sign(secret, rawBody);
        return Map.of("X-VLA-Timestamp", timestamp, "X-VLA-Signature", signature);
    }

    public static String toJson(WebhookPayload payload) {
        return GSON.toJson(payload);
    }

    private static final class InstantTypeAdapter
    extends TypeAdapter<Instant> {
        private InstantTypeAdapter() {
        }

        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }
}
