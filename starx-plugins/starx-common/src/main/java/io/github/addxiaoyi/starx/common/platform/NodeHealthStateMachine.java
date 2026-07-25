package io.github.addxiaoyi.starx.common.platform;

public final class NodeHealthStateMachine {
  private static final int[] WARMING_WEIGHTS = {10, 25, 50, 100};

  private State state = State.HEALTHY;
  private int missed;
  private int warmingStep;
  private int admissionWeight = 100;

  public synchronized Snapshot missedHeartbeat() {
    this.missed++;
    this.warmingStep = 0;
    if (this.missed == 1) {
      this.state = State.SUSPECT;
      this.admissionWeight = 50;
    } else if (this.missed == 2) {
      this.state = State.DRAINING;
      this.admissionWeight = 0;
    } else {
      this.state = State.OFFLINE;
      this.admissionWeight = 0;
    }
    return this.snapshot();
  }

  public synchronized Snapshot healthyHeartbeat() {
    if (this.state == State.SUSPECT) {
      this.resetHealthy();
      return this.snapshot();
    }
    if (this.state == State.DRAINING || this.state == State.OFFLINE || this.state == State.WARMING) {
      this.state = State.WARMING;
      this.missed = 0;
      this.admissionWeight = WARMING_WEIGHTS[this.warmingStep];
      this.warmingStep++;
      if (this.warmingStep == WARMING_WEIGHTS.length) this.resetHealthy();
      return this.snapshot();
    }
    this.resetHealthy();
    return this.snapshot();
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(this.state, this.admissionWeight, this.missed, this.warmingStep);
  }

  private void resetHealthy() {
    this.state = State.HEALTHY;
    this.missed = 0;
    this.warmingStep = 0;
    this.admissionWeight = 100;
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
