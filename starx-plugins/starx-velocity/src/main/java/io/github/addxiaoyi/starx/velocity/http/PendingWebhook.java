package io.github.addxiaoyi.starx.velocity.http;

import java.util.Objects;

public record PendingWebhook(String id, String url, String body, long createdAt) {
  public PendingWebhook {
    id = requireText(id, "id");
    url = requireText(url, "url");
    body = Objects.requireNonNull(body, "body");
    if (createdAt < 0L) throw new IllegalArgumentException("createdAt must not be negative");
  }

  private static String requireText(String value, String field) {
    String text = Objects.requireNonNull(value, field).trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return text;
  }
}
