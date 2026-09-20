package io.github.addxiaoyi.starx.common.binding;

@FunctionalInterface
public interface BindingChallengeAction<T> {
  boolean execute(String operationId, T target);
}
