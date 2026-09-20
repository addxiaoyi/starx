package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountBindingEntryContractTest {

  @Test
  void httpApiUsesBoundedExecutorAndClosesIt() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertFalse(source.contains("newCachedThreadPool"));
    assertTrue(source.contains("ExecutorService"));
    assertTrue(source.contains("shutdownNow"));
  }

  @Test
  void optionsRequestsShortCircuitAfterCorsResponse() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    int cors = source.indexOf("if (this.handleCors(exchange))");
    int rateLimit = source.indexOf("this.rateLimits.tryAcquire(clientIp)");
    int routeLookup = source.indexOf("Map<String, RouteHandler> methods = this.routes.get(path)");
    assertTrue(cors >= 0 && cors < rateLimit && cors < routeLookup);
    assertTrue(source.contains("return true;"));
  }

  @Test
  void corsHeadersAreAppliedBeforeEveryResponse() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("this.applyCorsHeaders(exchange);"));
    assertTrue(source.contains("Access-Control-Allow-Origin"));
    assertFalse(source.contains("set(\"Access-Control-Allow-Origin\", \"*\")"));
    String exchange = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/JsonHttpExchange.java"));
    assertFalse(exchange.contains("Access-Control-Allow-Origin"));
  }

  @Test
  void rateLimitRegistriesAreBoundedAndPruneExpiredEntries() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/http/BoundedRateLimitRegistry.java"));
    assertTrue(source.contains("limits.size() >= capacity"));
    assertTrue(source.contains("removeIf"));
    assertTrue(source.contains("return false"));
  }

  @Test
  void forwardedIpIsTrustedOnlyFromLocalProxy() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("isLoopbackAddress()"));
    assertTrue(source.contains("trustedForwardedIp"));
  }

  @Test
  void shortCircuitResponsesCloseHttpExchange() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("sendResponseHeaders(429, -1L);"));
    assertTrue(source.contains("sendResponseHeaders(405, -1L);"));
    assertTrue(source.contains("exchange.close();"));
  }

  @Test
  void jsonRequestsHaveBodySizeLimit() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/JsonHttpExchange.java"));
    assertTrue(source.contains("MAX_BODY_BYTES"));
    assertTrue(source.contains("Payload too large"));
  }

  @Test
  void oversizedPayloadsMapToPayloadTooLarge() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("Payload too large"));
    assertTrue(source.contains("status(413)"));
  }

  @Test
  void malformedContentLengthMapsToBadRequest() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("Invalid Content-Length"));
    assertTrue(source.contains("status(400)"));
  }

  @Test
  void forwardedIpValuesAreNormalizedAndBounded() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java"));
    assertTrue(source.contains("MAX_FORWARDED_IP_LENGTH"));
    assertTrue(source.contains("sanitizeForwardedIp"));
  }
  @Test
  void onlyEmailChallengeCanBindEmailFromVelocityHttpApi() throws Exception {
    Path source = Path.of("src/main/java/io/github/addxiaoyi/starx/velocity/http/HttpApiServer.java");
    String api = Files.readString(source);

    assertTrue(api.contains("new EmailChallengeHandler"));
    assertFalse(api.contains("new BindEmailHandler"));
  }
}
