package io.github.addxiaoyi.starx.velocity.http;

public final class WebhookDeliveryException extends RuntimeException {
  private final int statusCode;

  public WebhookDeliveryException(String message) {
    super(message);
    this.statusCode = -1;
  }

  public WebhookDeliveryException(int statusCode) {
    super("Webhook rejected with HTTP " + statusCode);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
