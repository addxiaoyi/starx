package io.github.addxiaoyi.starx.website;

public final class WebsiteSyncApiException extends Exception {
  private final int statusCode;
  private final String errorCode;

  public WebsiteSyncApiException(int statusCode, String errorCode, String message) {
    super(safeMessage(statusCode, errorCode, message));
    this.statusCode = statusCode;
    this.errorCode = errorCode == null || errorCode.isBlank() ? "http_error" : errorCode;
  }

  public WebsiteSyncApiException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.errorCode = "transport_error";
  }

  public int statusCode() {
    return this.statusCode;
  }

  public String errorCode() {
    return this.errorCode;
  }

  public boolean unauthorized() {
    return this.statusCode == 401;
  }

  public boolean retryable() {
    return this.statusCode == 0 || this.statusCode == 429 || this.statusCode >= 500;
  }

  private static String safeMessage(int statusCode, String code, String message) {
    String normalizedCode = code == null || code.isBlank() ? "http_error" : code;
    String normalizedMessage = message == null || message.isBlank() ? "request failed" : message;
    return "Website sync request failed: status=" + statusCode
        + " code=" + normalizedCode + " message=" + normalizedMessage;
  }
}
