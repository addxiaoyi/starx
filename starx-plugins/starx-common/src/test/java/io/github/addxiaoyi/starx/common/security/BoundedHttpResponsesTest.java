package io.github.addxiaoyi.starx.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

final class BoundedHttpResponsesTest {
  @Test
  void decodesSmallUtf8Body() throws Exception {
    HttpResponse.BodySubscriber<String> subscriber =
        BoundedHttpResponses.utf8(32).apply(null);
    subscriber.onSubscribe(subscription());
    subscriber.onNext(List.of(ByteBuffer.wrap("正版".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    subscriber.onComplete();
    assertEquals("正版", subscriber.getBody().toCompletableFuture().join());
  }

  @Test
  void rejectsBodyBeyondLimit() {
    HttpResponse.BodySubscriber<String> subscriber = BoundedHttpResponses.utf8(2).apply(null);
    subscriber.onSubscribe(subscription());
    subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {'a', 'b', 'c'})));
    subscriber.onComplete();
    assertThrows(Exception.class, () -> subscriber.getBody().toCompletableFuture().join());
  }

  private static Flow.Subscription subscription() {
    return new Flow.Subscription() {
      public void request(long count) { }
      public void cancel() { }
    };
  }
}
