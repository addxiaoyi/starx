/*
 * Copyright (c) 2024-2026 StarMC Team and contributors.
 * Use of this source code is governed by the MIT License.
 */
package io.github.addxiaoyi.starx.common.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.UUID;

/** Validates the identity fields returned by a Yggdrasil profile endpoint. */
public final class PremiumProfileVerifier {
  private static final String MINECRAFT_USERNAME = "[A-Za-z0-9_]{3,16}";

  private PremiumProfileVerifier() {}

  public static boolean matches(String body, UUID expectedUuid, String expectedUsername) {
    if (body == null || expectedUuid == null || expectedUsername == null
        || !expectedUsername.matches(MINECRAFT_USERNAME)) return false;
    try {
      JsonElement root = JsonParser.parseString(body);
      if (!root.isJsonObject()) return false;
      JsonElement id = root.getAsJsonObject().get("id");
      JsonElement name = root.getAsJsonObject().get("name");
      if (id == null || name == null || !id.isJsonPrimitive() || !name.isJsonPrimitive()
          || !id.getAsJsonPrimitive().isString() || !name.getAsJsonPrimitive().isString()) {
        return false;
      }
      String returnedName = name.getAsString().trim();
      if (!returnedName.matches(MINECRAFT_USERNAME)) return false;
      String rawId = id.getAsString().trim();
      if (!rawId.matches("[0-9A-Fa-f]{32}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-"
          + "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) return false;
      if (rawId.length() == 32) {
        rawId = rawId.substring(0, 8) + "-" + rawId.substring(8, 12) + "-"
            + rawId.substring(12, 16) + "-" + rawId.substring(16, 20) + "-"
            + rawId.substring(20);
      }
      return UUID.fromString(rawId).equals(expectedUuid)
          && expectedUsername.equalsIgnoreCase(returnedName);
    } catch (RuntimeException ignored) {
      return false;
    }
  }
}
