package io.github.addxiaoyi.starx.velocity.http;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.addxiaoyi.starx.common.crypto.HmacRequestSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class WebhookEmailSenderSignatureTest {
  @Test
  void signsEmailChallengeRequestWithHmacV2() {
    URI endpoint = URI.create("https://star-web.top/api/v1/plugin/email-challenge/send");
    String body = "{\"email\":\"player@example.com\",\"code\":\"123456\"}";

    Map<String, String> headers = WebhookEmailSender.signedHeaders(
        endpoint, body, "secret", "1784808000000");

    assertTrue(HmacRequestSigner.verify(
        "secret", "POST", "/api/v1/plugin/email-challenge/send",
        headers.get(WebhookClient.TIMESTAMP_HEADER), body,
        headers.get(WebhookClient.SIGNATURE_HEADER)));
  }

  @Test
  void discardsWebsiteResponseBody() throws Exception {
    HttpResponse.BodySubscriber<Void> subscriber = WebhookEmailSender.responseHandler()
        .apply(new ResponseInfo());
    subscriber.onSubscribe(new Flow.Subscription() {
      @Override public void request(long count) { }
      @Override public void cancel() { }
    });
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1024])));
    subscriber.onComplete();

    assertNull(subscriber.getBody().toCompletableFuture().get());
  }

  private static final class ResponseInfo implements HttpResponse.ResponseInfo {
    @Override public int statusCode() { return 200; }
    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
  }
}
