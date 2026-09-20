package io.github.addxiaoyi.starx.velocity.http;

import com.google.gson.Gson;
import io.github.addxiaoyi.starx.common.auth.EmailSender;
import io.github.addxiaoyi.starx.common.crypto.HmacRequestSigner;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

final class WebhookEmailSender implements EmailSender {
  private static final Gson JSON = new Gson();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final URI endpoint;
  private final String secret;

  WebhookEmailSender(StarxConfig.WebhookConfig config) {
    String url = Objects.requireNonNullElse(config.url(), "").trim();
    this.secret = Objects.requireNonNullElse(config.secret(), "").trim();
    if (url.isEmpty() || this.secret.isEmpty()) {
      throw new IllegalArgumentException("网站邮件网关 webhook.url/secret 未配置");
    }
    this.endpoint = URI.create(url).resolve("/api/v1/plugin/email-challenge/send");
  }

  @Override
  public void sendVerificationCode(String email, String code) {
    try {
      String body = JSON.toJson(Map.of("email", email, "code", code));
      String timestamp = String.valueOf(System.currentTimeMillis());
      HttpRequest.Builder builder = HttpRequest.newBuilder(this.endpoint)
          .timeout(Duration.ofSeconds(10))
          .POST(HttpRequest.BodyPublishers.ofString(body));
      signedHeaders(this.endpoint, body, this.secret, timestamp).forEach(builder::header);
      HttpRequest request = builder.build();
      HttpResponse<Void> response = this.http.send(request, responseHandler());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("网站邮件网关返回 HTTP " + response.statusCode());
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("网站邮件网关请求被中断", error);
    } catch (Exception error) {
      if (error instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("网站邮件网关请求失败", error);
    }
  }

  static Map<String, String> signedHeaders(
      URI endpoint, String body, String secret, String timestamp) {
    String signature = HmacRequestSigner.sign(
        secret, "POST", WebhookClient.requestTarget(endpoint.toString()), timestamp, body);
    return Map.of(
        "Content-Type", "application/json",
        WebhookClient.SIGNATURE_HEADER, signature,
        WebhookClient.TIMESTAMP_HEADER, timestamp);
  }

  static HttpResponse.BodyHandler<Void> responseHandler() {
    return HttpResponse.BodyHandlers.discarding();
  }
}
