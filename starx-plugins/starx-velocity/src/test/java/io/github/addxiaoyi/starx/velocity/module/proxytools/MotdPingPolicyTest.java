package io.github.addxiaoyi.starx.velocity.module.proxytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.Favicon;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MotdPingPolicyTest {

  @TempDir
  Path temp;

  @Test
  void usesMiniMessageAndModulesMotdOptions() {
    MotdModule.Config config = MotdModule.Config.from(new StarxConfig.ModuleConfig(true, Map.of(
        "normal", "<red>Normal",
        "maintenance", "<gradient:red:blue>Maintenance",
        "maximum-players", 20,
        "icon-path", "server-icon.png")));

    assertEquals(Component.text("Normal", net.kyori.adventure.text.format.NamedTextColor.RED), config.normalMotd());
    assertEquals("server-icon.png", config.faviconPath());
    assertEquals(20, config.maximumPlayers());
  }

  @Test
  void updatesActualOnlineAndMaximumWithoutDroppingPingMetadata() {
    Favicon incomingFavicon = new Favicon("data:image/png;base64,AA==");
    ServerPing incoming = ServerPing.builder()
        .version(new ServerPing.Version(774, "1.21.11"))
        .samplePlayers(new ServerPing.SamplePlayer("sample", java.util.UUID.randomUUID()))
        .modType("FML")
        .favicon(incomingFavicon)
        .description(Component.text("old"))
        .onlinePlayers(2)
        .maximumPlayers(5)
        .build();

    ServerPing updated = MotdModule.applyPing(incoming, Component.text("new"), 25, 20, null);

    assertEquals(25, updated.getPlayers().orElseThrow().getOnline());
    assertEquals(25, updated.getPlayers().orElseThrow().getMax());
    assertEquals(Component.text("new"), updated.getDescriptionComponent());
    assertEquals(incomingFavicon, updated.getFavicon().orElseThrow());
    assertEquals(1, updated.getPlayers().orElseThrow().getSample().size());
    assertEquals("FML", updated.getModinfo().orElseThrow().getType());
  }

  @Test
  void loadsOnlySafeConfiguredFaviconFromDataDirectory() throws Exception {
    Path favicon = temp.resolve("server-icon.png");
    Files.write(favicon, png());

    Favicon loaded = MotdModule.loadFavicon(temp, "server-icon.png", message -> { });
    assertNotNull(loaded);
    assertTrue(MotdModule.loadFavicon(temp, "../server-icon.png", message -> { }) == null);
  }

  @Test
  void rejectsInvalidMaximum() {
    assertThrows(IllegalArgumentException.class, () -> new MotdModule.PingPolicy(0));
  }

  private static byte[] png() throws Exception {
    BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, Color.RED.getRGB());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }
}