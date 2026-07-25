package io.github.addxiaoyi.starx.velocity.http.admin;

import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import io.github.addxiaoyi.starx.common.auth.WebLoginApprovalGateway;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class CrossDeviceLoginApprovalGateway implements WebLoginApprovalGateway {
  private final CrossDeviceApprovalService approvals;
  private final String websiteOrigin;

  public CrossDeviceLoginApprovalGateway(
      CrossDeviceApprovalService approvals, String websiteOrigin) {
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.websiteOrigin = WebsiteOriginResolver.fromUrl(websiteOrigin);
  }

  @Override
  public String request(UUID playerId, String username, AuthLease lease) {
    CrossDeviceApprovalService.Challenge challenge =
        this.approvals.createLogin(playerId, username, lease);
    return this.websiteOrigin + "/minecraft/approve?token="
        + URLEncoder.encode(challenge.token(), StandardCharsets.UTF_8)
        + "&action=approve_login";
  }
}
