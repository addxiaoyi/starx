package io.github.addxiaoyi.starx.common.platform;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class NodeHealthStateMachine {
  private static final int[] WARMING_WEIGHTS = {10, 25, 50, 100};

  private final AtomicReference<State> state = new AtomicReference<>(State.HEALTHY);
  private final AtomicInteger missed = new AtomicInteger(0);
  private final AtomicInteger warmingStep = new AtomicInteger(0);
  private final AtomicInteger admissionWeight = new AtomicInteger(100);

  public Snapshot missedHeartbeat() {
    int currentMissed = missed.incrementAndGet();
    warmingStep.set(0);
    
    State newState;
    int newWeight;
    if (currentMissed == 1) {
      newState = State.SUSPECT;
      newWeight = 50;
    } else if (currentMissed == 2) {
      newState = State.DRAINING;
      newWeight = 0;
    } else {
      newState = State.OFFLINE;
      newWeight = 0;
    }
    
    state.set(newState);
    admissionWeight.set(newWeight);
    return snapshot();
  }

  public Snapshot healthyHeartbeat() {
    State currentState = state.get();
    
    if (currentState == State.SUSPECT) {
      resetHealthy();
      return snapshot();
    }
    
    if (currentState == State.DRAINING || currentState == State.OFFLINE || currentState == State.WARMING) {
      state.set(State.WARMING);
      missed.set(0);
      
      int currentWarmingStep = warmingStep.getAndIncrement();
      admissionWeight.set(WARMING_WEIGHTS[currentWarmingStep]);
      
      if (currentWarmingStep + 1 == WARMING_WEIGHTS.length) {
        resetHealthy();
      }
      return snapshot();
    }
    
    resetHealthy();
    return snapshot();
  }

  public Snapshot snapshot() {
    return new Snapshot(state.get(), admissionWeight.get(), missed.get(), warmingStep.get());
  }

  private void resetHealthy() {
    state.set(State.HEALTHY);
    missed.set(0);
    warmingStep.set(0);
    admissionWeight.set(100);
  }

  public enum State {
    HEALTHY,
    SUSPECT,
    DRAINING,
    OFFLINE,
    WARMING
  }

  public record Snapshot(State state, int admissionWeight, int missedHeartbeats, int warmingStep) { }
}
