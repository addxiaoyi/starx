package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class NapCatHttpBodyHandlerTest {
  @Test
  void apiResponsesAreDiscardedInsteadOfBufferedAsStrings() throws Exception {
    HttpResponse.BodySubscriber<Void> subscriber = NapCatWebSocketClient.apiResponseHandler()
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
