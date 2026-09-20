package io.github.addxiaoyi.starx.velocity.http.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrossDeviceLoginApprovalGatewayTest {
  @Test
  void createsAWebsiteLinkBoundToTheLiveLease() {
    UUID playerId = UUID.randomUUID();
    AuthLease lease = AuthLease.create();
    CrossDeviceApprovalService approvals = new CrossDeviceApprovalService();
    CrossDeviceLoginApprovalGateway gateway = new CrossDeviceLoginApprovalGateway(
        approvals, "https://star-web.top/");

    String url = gateway.request(playerId, "Alex", lease);
    String token = Arrays.stream(URI.create(url).getRawQuery().split("&"))
        .filter(part -> part.startsWith("token="))
        .map(part -> part.substring("token=".length()))
        .findFirst().orElseThrow();

    assertTrue(url.endsWith("&action=approve_login"));
    assertEquals(CrossDeviceApprovalService.Status.APPROVED,
        approvals.approveAndExecute(
            token, playerId, "Alex", CrossDeviceApprovalService.Action.APPROVE_LOGIN,
            challenge -> lease.equals(challenge.authLease())).status());
  }
}
