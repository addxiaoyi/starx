package io.github.addxiaoyi.starx.server;

import java.util.List;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies the first usable title placeholder exposed by an installed title provider. */
final class BackendTabTitleListener implements Listener {
  private static final List<String> PROVIDERS = List.of(
      "PlayerTitle", "zPrefix", "PrefixManager", "KDPrefix", "LuckPerms", "Vault");
  private static final List<String> PLACEHOLDERS = List.of(
      "%zprefix_current%", "%zprefix_prefix%", "%playertitle_title%", "%playertitle_prefix%",
      "%prefixmanager_prefix%", "%kdprefix_prefix%", "%luckperms_prefix%", "%vault_prefix%");

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    apply(event.getPlayer());
  }

  void applyAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      apply(player);
    }
  }

  private void apply(Player player) {
    if (!hasProvider()) {
      return;
    }
    for (String placeholder : PLACEHOLDERS) {
      String value = PlaceholderAPI.setPlaceholders(player, placeholder).trim();
      if (value.isBlank() || value.equals(placeholder)) {
        continue;
      }
      player.setPlayerListName(value + " " + player.getName());
      return;
    }
    player.setPlayerListName(player.getName());
  }

  private boolean hasProvider() {
    return PROVIDERS.stream().anyMatch(name -> Bukkit.getPluginManager().isPluginEnabled(name));
  }
}
