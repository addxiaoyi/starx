package io.github.addxiaoyi.starx.common.platform;

import io.github.addxiaoyi.starx.common.database.JdbcRuntimeSettingRepository;
import java.util.Objects;

public final class MaintenanceStateService {
  private static final String ENABLED_KEY = "maintenance.enabled";

  private final JdbcRuntimeSettingRepository settings;

  public MaintenanceStateService(JdbcRuntimeSettingRepository settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public boolean load() {
    return settings.getBoolean(ENABLED_KEY, false);
  }

  public void save(boolean enabled, long updatedAt) {
    settings.putBoolean(ENABLED_KEY, enabled, updatedAt);
  }
}
