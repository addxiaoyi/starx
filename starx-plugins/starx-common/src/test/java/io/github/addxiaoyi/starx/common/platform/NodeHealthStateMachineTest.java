package io.github.addxiaoyi.starx.common.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NodeHealthStateMachineTest {
  @Test
  void drainsBeforeMarkingNodeOffline() {
    NodeHealthStateMachine node = new NodeHealthStateMachine();

    assertEquals(NodeHealthStateMachine.State.SUSPECT, node.missedHeartbeat().state());
    assertEquals(NodeHealthStateMachine.State.DRAINING, node.missedHeartbeat().state());
    assertEquals(0, node.snapshot().admissionWeight());
    assertEquals(NodeHealthStateMachine.State.OFFLINE, node.missedHeartbeat().state());
  }

  @Test
  void warmsGraduallyAfterAnOfflineNodeRecovers() {
    NodeHealthStateMachine node = new NodeHealthStateMachine();
    node.missedHeartbeat();
    node.missedHeartbeat();
    node.missedHeartbeat();

    assertEquals(10, node.healthyHeartbeat().admissionWeight());
    assertEquals(25, node.healthyHeartbeat().admissionWeight());
    assertEquals(50, node.healthyHeartbeat().admissionWeight());
    NodeHealthStateMachine.Snapshot recovered = node.healthyHeartbeat();
    assertEquals(NodeHealthStateMachine.State.HEALTHY, recovered.state());
    assertEquals(100, recovered.admissionWeight());
  }

  @Test
  void oneSuccessfulHeartbeatClearsASuspectWithoutCyclingOffline() {
    NodeHealthStateMachine node = new NodeHealthStateMachine();
    node.missedHeartbeat();

    assertEquals(NodeHealthStateMachine.State.HEALTHY, node.healthyHeartbeat().state());
  }
}
