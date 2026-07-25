package io.github.addxiaoyi.starx.server;

import java.awt.image.BufferedImage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

final class QrMapItem {
  private QrMapItem() {
  }

  static void give(Player player, String otpauthUri) {
    BufferedImage image = QrCodeImage.render(otpauthUri);
    MapView view = Bukkit.createMap(player.getWorld());
    view.getRenderers().forEach(view::removeRenderer);
    view.addRenderer(new SingleImageRenderer(image));
    ItemStack map = new ItemStack(Material.FILLED_MAP);
    MapMeta meta = (MapMeta) map.getItemMeta();
    meta.setMapView(view);
    meta.displayName(Component.text("StarMC 2FA 二维码"));
    map.setItemMeta(meta);
    player.getInventory().addItem(map).values().forEach(item ->
        player.getWorld().dropItemNaturally(player.getLocation(), item));
  }

  private static final class SingleImageRenderer extends MapRenderer {
    private final BufferedImage image;
    private boolean rendered;

    private SingleImageRenderer(BufferedImage image) {
      super(false);
      this.image = image;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
      if (this.rendered) return;
      canvas.drawImage(0, 0, this.image);
      this.rendered = true;
    }
  }
}
