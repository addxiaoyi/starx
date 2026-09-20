package io.github.addxiaoyi.starx.common.auth;

import java.util.UUID;

@FunctionalInterface
public interface WebLoginApprovalGateway {
  String request(UUID playerId, String username, AuthLease lease);
}
