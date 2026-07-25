package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class JsonHttpExchangeTest {
  @Test
  void authenticationStateIsFalseUntilExplicitlyMarked() {
    JsonHttpExchange exchange = new JsonHttpExchange(null);

    assertFalse(exchange.authenticated());
    exchange.markAuthenticated();
    assertTrue(exchange.authenticated());
  }

  @Test
  void requestTargetPreservesRawQueryEncoding() {
    URI uri = URI.create("/v1/user/detail?name=Alex%20Chen&source=web");

    assertEquals(
        "/v1/user/detail?name=Alex%20Chen&source=web",
        JsonHttpExchange.requestTarget(uri));
  }

  @Test
  void requestTargetOmitsQuestionMarkWhenQueryIsAbsent() {
    assertEquals("/v1/health", JsonHttpExchange.requestTarget(URI.create("/v1/health")));
  }

  @Test
  void decodesUtf8QueryValues() {
    assertEquals("玩家 名称", JsonHttpExchange.decodeQueryValue("%E7%8E%A9%E5%AE%B6+%E5%90%8D%E7%A7%B0"));
  }

  @Test
  void rejectsMalformedPercentEncodingAsClientInput() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> JsonHttpExchange.decodeQueryValue("broken%2"));

    assertEquals("Invalid query encoding", error.getMessage());
  }
}
