package io.github.addxiaoyi.starx.common.database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcUuidQuery {

  private JdbcUuidQuery() {
  }

  public static List<UUID> distinct(Collection<UUID> uuids) {
    Objects.requireNonNull(uuids, "uuids");
    LinkedHashSet<UUID> unique = new LinkedHashSet<>();
    for (UUID uuid : uuids) unique.add(Objects.requireNonNull(uuid, "uuid"));
    return new ArrayList<>(unique);
  }

  public static String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  public static void bind(PreparedStatement statement, Collection<UUID> uuids) throws SQLException {
    int index = 1;
    for (UUID uuid : uuids) statement.setString(index++, uuid.toString());
  }
}
