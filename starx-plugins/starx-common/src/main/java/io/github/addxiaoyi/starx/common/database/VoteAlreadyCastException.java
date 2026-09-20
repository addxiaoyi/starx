package io.github.addxiaoyi.starx.common.database;

public final class VoteAlreadyCastException extends IllegalStateException {
  public VoteAlreadyCastException(String message, Throwable cause) {
    super(message, cause);
  }
}
