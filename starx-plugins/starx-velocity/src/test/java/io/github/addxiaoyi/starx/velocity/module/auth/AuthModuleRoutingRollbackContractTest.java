package io.github.addxiaoyi.starx.velocity.module.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthModuleRoutingRollbackContractTest {
  @Test
  void failedAuthenticatedRoutingRevokesTheAuthSession() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java"));

    int finishStart = source.indexOf("private Optional<Component> finishTrustedLogin");
    int routeStart = source.indexOf("private boolean routeAuthenticatedPlayer");
    String finish = source.substring(finishStart, routeStart);
    String route = source.substring(routeStart);

    assertTrue(finish.contains("authService.forceLogoutInternal(player.getUniqueId()"));
    assertTrue(route.contains("authService.forceLogoutInternal(player.getUniqueId()"));
  }

  @Test
  void webApprovalReportsRoutingFailureToTheApprovalCaller() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/velocity/module/auth/AuthModule.java"));

    int approvalStart = source.indexOf("public boolean approveWebLogin");
    int approvalEnd = source.indexOf("  @Override", approvalStart);
    String approval = source.substring(approvalStart, approvalEnd);

    assertTrue(approval.contains("boolean routed = this.routeAuthenticatedPlayer(player)"));
    assertTrue(approval.indexOf("boolean routed = this.routeAuthenticatedPlayer(player)")
        < approval.indexOf("网页登录确认成功"));
    assertTrue(source.contains("private boolean routeAuthenticatedPlayer"));
  }
}
