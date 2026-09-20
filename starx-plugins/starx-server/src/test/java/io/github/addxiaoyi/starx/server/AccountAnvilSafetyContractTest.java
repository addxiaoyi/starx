package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;

class AccountAnvilSafetyContractTest {
  @Test
  void accountInputClearsEveryAnvilCost() {
    Map<String, Object> calls = new LinkedHashMap<>();
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        if (args != null && args.length == 1) {
          calls.put(method.getName(), args[0]);
        }
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
      }
    };
    AnvilView view = (AnvilView) Proxy.newProxyInstance(
        AnvilView.class.getClassLoader(), new Class<?>[] {AnvilView.class}, handler);

    AccountAnvilController.makeAnvilFree(view);

    assertEquals(0, calls.get("setRepairItemCountCost"));
    assertEquals(0, calls.get("setRepairCost"));
    assertEquals(Integer.MAX_VALUE, calls.get("setMaximumRepairCost"));
    assertEquals(true, calls.get("bypassEnchantmentLevelRestriction"));
  }

  @Test
  void inventoryTransitionsRunAfterClickEventAndFailuresUseActionBar() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/io/github/addxiaoyi/starx/server/AccountAnvilController.java"));

    assertTrue(source.contains("this.runNextTick(player, player::closeInventory)"));
    assertTrue(source.contains("this.open(player, selected)"));
    assertTrue(source.contains("view.setItem(0, inputItem())"));
    assertTrue(source.indexOf("player.openInventory(anvil)")
        < source.indexOf("view.setItem(0, inputItem())"));
    assertTrue(source.contains("player.updateInventory()"));
    assertFalse(source.contains("session.inventory()"));
    assertFalse(source.contains("menu.inventory() == event.getInventory()"));
    assertTrue(source.contains("implements InventoryHolder"));
    assertTrue(source.contains("view.getTopInventory().getHolder() == this"));
    assertTrue(source.contains("session.owns(event.getView(), player.getUniqueId())"));
    assertTrue(source.contains("menu.owns(event.getView(), player.getUniqueId())"));
    assertTrue(source.contains("view == null || !menuSession.owns(view, player.getUniqueId())"));
    assertTrue(source.contains("this.menus.remove(player.getUniqueId(), menuSession)"));
    assertTrue(source.contains("this.sessions.remove(player.getUniqueId(), session)"));
    assertTrue(source.contains("账号输入界面未能打开，请稍后重试。"));
    assertTrue(source.contains("player.sendActionBar(message)"));
    int prepare = source.indexOf("public void onPrepare");
    int free = source.indexOf("makeAnvilFree(event.getView())", prepare);
    int result = source.indexOf("event.setResult(confirmItem())", prepare);
    assertTrue(prepare >= 0 && free > prepare && result > free);
    assertFalse(source.contains("event.getView().setRepairCost(0);"));
  }

  @Test
  void unavailableEmailGatewayHasAPlayerSafeMessage() {
    assertEquals(
        "邮箱绑定暂不可用：服务器尚未配置邮件发送服务，请联系管理员。",
        AccountAnvilController.playerError("网站邮件网关 webhook.url/secret 未配置"));
    assertEquals("操作失败，请稍后重试。", AccountAnvilController.playerError("  "));
  }
}
