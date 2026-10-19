package io.github.addxiaoyi.starx.website;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

final class BlockingHttpTransport {
  private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
  private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
  private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
      daemonFactory());

  private BlockingHttpTransport() {
  }

  static <T> HttpResponse<T> send(
      HttpRequest request,
      HttpResponse.BodyHandler<T> bodyHandler,
      Duration connectTimeout,
      Duration requestTimeout,
      Duration totalTimeout
  ) throws IOException, InterruptedException {
    URI uri = request.uri();
    if (!"http".equalsIgnoreCase(uri.getScheme())
        && !"https".equalsIgnoreCase(uri.getScheme())) {
      throw new IOException("Unsupported HTTP scheme: " + uri.getScheme());
    }
    byte[] requestBody = collectRequestBody(request);
    URLConnection opened = uri.toURL().openConnection();
    if (!(opened instanceof HttpURLConnection connection)) {
      throw new IOException("Unsupported HTTP connection for " + uri);
    }
    Duration timeout = request.timeout().orElse(requestTimeout);
    connection.setConnectTimeout(timeoutMillis(connectTimeout));
    connection.setReadTimeout(timeoutMillis(timeout));
    connection.setInstanceFollowRedirects(false);
    connection.setUseCaches(false);
    connection.setRequestMethod(request.method());
    for (Map.Entry<String, List<String>> header : request.headers().map().entrySet()) {
      for (String value : header.getValue()) {
        connection.addRequestProperty(header.getKey(), value);
      }
    }
    Thread caller = Thread.currentThread();
    AtomicBoolean deadlineExpired = new AtomicBoolean();
    ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
      deadlineExpired.set(true);
      connection.disconnect();
      caller.interrupt();
    }, Math.max(1L, totalTimeout.toMillis()), TimeUnit.MILLISECONDS);
    try {
      if (request.bodyPublisher().isPresent()) {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(requestBody.length);
        try (OutputStream output = connection.getOutputStream()) {
          output.write(requestBody);
        }
      }

      int status = connection.getResponseCode();
      HttpHeaders headers = responseHeaders(connection.getHeaderFields());
      byte[] responseBody = readBody(status >= 400 ? connection.getErrorStream()
          : connection.getInputStream());
      HttpResponse.ResponseInfo info = new ResponseInfo(status, headers, HttpClient.Version.HTTP_1_1);
      T body = decode(bodyHandler, info, responseBody);
      return new Response<>(status, request, headers, uri, body);
    } finally {
      deadline.cancel(false);
      connection.disconnect();
      if (Thread.interrupted() && !deadlineExpired.get()) {
        throw new InterruptedException("HTTP request exceeded its total deadline: " + uri);
      }
      if (deadlineExpired.get()) {
        throw new IOException("HTTP request exceeded its total deadline: " + uri);
      }
    }
  }

  private static HttpHeaders responseHeaders(Map<String, List<String>> source) {
    Map<String, List<String>> filtered = new HashMap<>();
    source.forEach((name, values) -> {
      if (name != null && values != null) filtered.put(name, values);
    });
    return HttpHeaders.of(filtered, (name, value) -> true);
  }

  private static ThreadFactory daemonFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return task -> {
      Thread thread = new Thread(task, "starx-http-deadline-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static byte[] collectRequestBody(HttpRequest request)
      throws IOException, InterruptedException {
    if (request.bodyPublisher().isEmpty()) {
      return new byte[0];
    }
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private Flow.Subscription subscription;

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(ByteBuffer item) {
        if (this.bytes.size() + item.remaining() > MAX_REQUEST_BYTES) {
          this.subscription.cancel();
          result.completeExceptionally(new IOException("HTTP request body is too large"));
          return;
        }
        byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        this.bytes.writeBytes(chunk);
      }

      @Override
      public void onError(Throwable error) {
        result.completeExceptionally(error);
      }

      @Override
      public void onComplete() {
        result.complete(this.bytes.toByteArray());
      }
    });
    try {
      return result.get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      throw new IOException("HTTP request body publisher timed out", error);
    } catch (java.util.concurrent.ExecutionException error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      if (cause instanceof IOException ioError) throw ioError;
      throw new IOException("HTTP request body publisher failed", cause);
    }
  }

  private static byte[] readBody(InputStream input) throws IOException {
    if (input == null) return new byte[0];
    try (input) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (bytes.size() + read > MAX_RESPONSE_BYTES) {
          throw new IOException("HTTP response body is too large");
        }
        bytes.write(buffer, 0, read);
      }
      return bytes.toByteArray();
    }
  }

  private static <T> T decode(
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.ResponseInfo info,
      byte[] body
  ) throws IOException, InterruptedException {
    HttpResponse.BodySubscriber<T> subscriber = handler.apply(info);
    subscriber.onSubscribe(new Flow.Subscription() {
      @Override
      public void request(long count) {
      }

      @Override
      public void cancel() {
      }
    });
    subscriber.onNext(List.of(ByteBuffer.wrap(body)));
    subscriber.onComplete();
    try {
      return subscriber.getBody().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      throw new IOException("HTTP response body handler timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw error;
    } catch (java.util.concurrent.ExecutionException error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      throw new IOException("HTTP response body handler failed", cause);
    }
  }

  private static int timeoutMillis(Duration timeout) {
    long millis = Math.max(1, timeout.toMillis());
    return (int) Math.min(Integer.MAX_VALUE, millis);
  }

  private record ResponseInfo(
      int statusCode,
      HttpHeaders headers,
      HttpClient.Version version
  ) implements HttpResponse.ResponseInfo {
  }

  private record Response<T>(
      int statusCode,
      HttpRequest request,
      HttpHeaders headers,
      URI uri,
      T body
  ) implements HttpResponse<T> {
    @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
    @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
  }
}
