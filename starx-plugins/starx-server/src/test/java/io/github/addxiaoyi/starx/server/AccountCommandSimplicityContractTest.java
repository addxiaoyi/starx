package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountCommandSimplicityContractTest {
  @Test
  void exposesOneShortPlayerCommandWithSimpleActions() throws IOException {
    String pluginYaml = Files.readString(projectFile("src/main/resources/plugin.yml"));
    String controller = Files.readString(projectFile(
        "src/main/java/io/github/addxiaoyi/starx/server/AccountAnvilController.java"));

    assertTrue(pluginYaml.contains("  sx:"));
    assertTrue(pluginYaml.contains("aliases: [starxaccount, account]"));
    assertTrue(controller.contains("args.length == 0"));
    assertTrue(controller.contains("this.openMenu(player)"));
    assertTrue(controller.contains(
        "new AccountInventoryHolder(player.getUniqueId(), Screen.MENU)"));
    assertTrue(controller.contains("Bukkit.createInventory(holder, 9"));
    assertTrue(controller.contains("new MenuSession(holder)"));
    assertTrue(controller.contains("case 1 -> Mode.EMAIL"));
    assertTrue(controller.contains("case 3 -> Mode.TOTP"));
    assertTrue(controller.contains("case 5 -> Mode.TOTP_DISABLE"));
    assertTrue(controller.contains("case 7 -> Mode.TOTP_RESET"));
    assertTrue(controller.contains("case \"\u90ae\u7bb1\""));
    assertTrue(controller.contains("case \"\u9a8c\u8bc1\""));
    assertTrue(controller.contains("case \"\u5173\u95ed\""));
    assertTrue(controller.contains("case \"\u91cd\u7f6e\""));
    assertFalse(controller.contains("\u7528\u6cd5\uff1a/starxaccount <"));
    assertFalse(controller.contains("event.getInventory().setRepairCost(0)"));
    assertFalse(controller.contains("anvil.getRenameText()"));
    assertTrue(controller.contains("this.runNextTick(player, () -> this.open(player, selected))"));
    assertTrue(controller.contains("makeAnvilFree(event.getView())"));
    assertTrue(controller.contains("anvilView.getRenameText()"));
  }

  private static Path projectFile(String relative) {
    Path current = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
      Path file = current.resolve("starx-plugins/starx-server").resolve(relative);
      if (Files.isRegularFile(file)) return file;
    }
    throw new IllegalStateException("StarX server project file is unavailable: " + relative);
  }
}
