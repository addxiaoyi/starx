package io.github.addxiaoyi.starx.common.auth.uniauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UniAuthClientProfileTest {
  @Test
  void parsesNestedPlayerProfile() {
    UniAuthClient.PlayerProfileResponse profile = UniAuthClient.parseProfileResponse(
        JsonParser.parseString("""
            {"code":200,"data":{"profile":{"exists":true,"registered":true,"name":"Alice","uuid":"11111111-1111-1111-1111-111111111111","userId":"user-42","email":"alice@example.test"}}}
            """).getAsJsonObject());
    assertTrue(profile.success());
    assertTrue(profile.exists());
    assertTrue(profile.registered());
    assertEquals("Alice", profile.username());
    assertEquals("11111111-1111-1111-1111-111111111111", profile.uuid());
    assertEquals("user-42", profile.externalUserId());
    assertEquals("alice@example.test", profile.email());
  }

  @Test
  void parsesLegacyDottedFields() {
    UniAuthClient.PlayerProfileResponse profile = UniAuthClient.parseProfileResponse(
        JsonParser.parseString("""
            {"code":200,"data":{"profile.exists":true,"profile.registered":false,"external_user_id":"legacy-7","mail":"legacy@example.test"}}
            """).getAsJsonObject());
    assertTrue(profile.success());
    assertTrue(profile.exists());
    assertEquals("IMPORTED", profile.status());
    assertEquals("legacy-7", profile.externalUserId());
    assertEquals("legacy@example.test", profile.email());
  }

  @Test
  void unverifiedEmailStillConfirmsPasswordForLocalMigration() {
    UniAuthClient.LoginResponse login = UniAuthClient.parseLoginResponse(
        JsonParser.parseString("""
            {"success":false,"code":403,"message":"Email is not verified"}
            """).getAsJsonObject());

    assertTrue(login.success());
    assertTrue(login.requiresLocalMigration());
    assertEquals("邮箱未验证，已转为本地认证", login.message());
  }

  @Test
  void wrongPasswordCannotStartLocalMigration() {
    UniAuthClient.LoginResponse login = UniAuthClient.parseLoginResponse(
        JsonParser.parseString("""
            {"success":false,"code":401,"message":"Invalid password"}
            """).getAsJsonObject());

    assertFalse(login.success());
    assertFalse(login.requiresLocalMigration());
  }

  @Test
  void remoteServiceFailureIsNotReportedAsCredentialFailure() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/publickey", exchange -> {
      byte[] body = "temporarily unavailable".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(503, body.length);
      try (var output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
    try {
      UniAuthClient client = new UniAuthClient(new UniAuthConfig(
          true,
          "http://127.0.0.1:" + server.getAddress().getPort() + "/",
          "test-key",
          1000,
          true));
      UniAuthClient.LoginResponse login = client.login("Alice", "password").join();

      assertFalse(login.success());
      assertTrue(login.serviceUnavailable());
      assertEquals("认证服务暂时不可用，请稍后重试", login.message());

      UniAuthClient.StatusResponse status = client.fetchStatus("Alice").join();
      assertFalse(status.success());
      assertFalse(status.exists());
      assertTrue(status.serviceUnavailable());
    } finally {
      server.stop(0);
    }
  }
}
