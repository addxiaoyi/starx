package io.github.addxiaoyi.starx.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.meta.ItemMeta;

final class AccountAnvilController implements CommandExecutor, Listener {
  private final StarxServerPlugin plugin;
  private volatile StarxAccountClient client;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, MenuSession> menus = new ConcurrentHashMap<>();
  private final Map<UUID, Long> activeRequests = new ConcurrentHashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private volatile boolean closed;

  AccountAnvilController(StarxServerPlugin plugin, StarxAccountClient client) {
    this.plugin = plugin;
    this.client = client;
  }

  void updateClient(StarxAccountClient client) {
    this.client = client;
  }

  @Override
  public boolean onCommand(
      CommandSender sender,
      Command command,
      String label,
      String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("此命令仅限玩家使用");
      return true;
    }
    if (args.length == 0) {
      this.openMenu(player);
      return true;
    }
    if (args.length != 1) {
      player.sendMessage(Component.text("请只选择一个操作", NamedTextColor.RED));
      return true;
    }
    AccountApprovalAction approvalAction = AccountApprovalAction.fromCommand(args[0]);
    if (approvalAction != null) {
      this.openApproval(player, approvalAction);
      return true;
    }
    Mode mode = Mode.from(args[0]);
    if (mode == null) {
      player.sendMessage(Component.text("未知账号操作", NamedTextColor.RED));
      return true;
    }
    this.open(player, mode);
    return true;
  }

  private void openMenu(Player player) {
    if (this.closed || !player.isOnline()) return;
    AccountInventoryHolder holder =
        new AccountInventoryHolder(player.getUniqueId(), Screen.MENU);
    Inventory menu = Bukkit.createInventory(holder, 9, Component.text("StarX 账号安全"));
    holder.attach(menu);
    menu.setItem(1, menuItem(Material.PAPER, "绑定邮箱", NamedTextColor.AQUA));
    menu.setItem(3, menuItem(Material.LIME_DYE, "开启二步验证", NamedTextColor.GREEN));
    menu.setItem(5, menuItem(Material.RED_DYE, "关闭二步验证", NamedTextColor.RED));
    menu.setItem(7, menuItem(Material.MAP, "重置恢复码", NamedTextColor.GOLD));
    this.sessions.remove(player.getUniqueId());
    MenuSession menuSession = new MenuSession(holder);
    this.menus.put(player.getUniqueId(), menuSession);
    InventoryView view = player.openInventory(menu);
    if (view == null || !menuSession.owns(view, player.getUniqueId())) {
      this.menus.remove(player.getUniqueId(), menuSession);
      sendFailure(player, "账号安全菜单未能打开，请稍后重试。");
    }
  }

  private static ItemStack menuItem(Material material, String name, NamedTextColor color) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(name, color));
    item.setItemMeta(meta);
    return item;
  }

  private void openApproval(Player player, AccountApprovalAction action) {
    if (this.client == null) {
      player.sendMessage(Component.text(
          "跨端授权暂不可用：子服未配置 StarX API", NamedTextColor.RED));
      return;
    }
    long requestGeneration = this.beginRequest(player);
    player.sendMessage(Component.text("正在创建一次性授权二维码…", NamedTextColor.GRAY));
    this.client.createApproval(player.getUniqueId(), player.getName(), action.apiName())
        .whenComplete((reply, error) -> player.getScheduler().run(this.plugin, ignored -> {
          if (!this.isCurrent(player, requestGeneration)) return;
          if (error != null) {
            player.sendMessage(Component.text("创建失败：" + rootMessage(error), NamedTextColor.RED));
            return;
          }
          if (!reply.ok() || reply.url().isBlank()) {
            player.sendMessage(Component.text(reply.message(), NamedTextColor.RED));
            return;
          }
          QrMapItem.give(player, reply.url());
          player.sendMessage(Component.text(action.title() + "二维码已放入背包", NamedTextColor.GREEN));
          player.sendMessage(Component.text("点击这里也可在浏览器打开", NamedTextColor.AQUA)
              .clickEvent(ClickEvent.openUrl(reply.url())));
          player.sendMessage(Component.text(
              "链接五分钟内有效且只能使用一次，请核对网站显示的角色名后确认。",
              NamedTextColor.YELLOW));
        }, null));
  }

  private void open(Player player, Mode mode) {
    if (this.closed || !player.isOnline()) return;
    if (this.client == null) {
      player.sendMessage(Component.text(
          "账号界面暂不可用：子服未配置 bridge.heartbeat.api-key", NamedTextColor.RED));
      return;
    }
    AccountInventoryHolder holder =
        new AccountInventoryHolder(player.getUniqueId(), Screen.ANVIL);
    Inventory anvil = Bukkit.createInventory(holder, org.bukkit.event.inventory.InventoryType.ANVIL,
        Component.text(mode.title, NamedTextColor.DARK_PURPLE));
    holder.attach(anvil);
    this.activeRequests.remove(player.getUniqueId());
    Session session = new Session(mode, holder);
    this.sessions.put(player.getUniqueId(), session);
    InventoryView view = player.openInventory(anvil);
    if (!(view instanceof AnvilView anvilView)
        || !session.owns(view, player.getUniqueId())) {
      this.sessions.remove(player.getUniqueId(), session);
      sendFailure(player, "账号输入界面未能打开，请稍后重试。");
      return;
    }
    makeAnvilFree(anvilView);
    view.setItem(0, inputItem());
    player.updateInventory();
    player.sendMessage(Component.text(mode.hint, NamedTextColor.GRAY));
  }

  @EventHandler
  public void onPrepare(PrepareAnvilEvent event) {
    Player player = player(event.getView());
    Session session = player == null ? null : this.sessions.get(player.getUniqueId());
    if (session == null || !session.owns(event.getView(), player.getUniqueId())) {
      return;
    }
    makeAnvilFree(event.getView());
    event.setResult(confirmItem());
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    MenuSession menu = this.menus.get(player.getUniqueId());
    if (menu != null && menu.owns(event.getView(), player.getUniqueId())) {
      event.setCancelled(true);
      Mode selected = switch (event.getRawSlot()) {
        case 1 -> Mode.EMAIL;
        case 3 -> Mode.TOTP;
        case 5 -> Mode.TOTP_DISABLE;
        case 7 -> Mode.TOTP_RESET;
        default -> null;
      };
      if (selected != null) {
        this.menus.remove(player.getUniqueId());
        this.runNextTick(player, () -> this.open(player, selected));
      }
      return;
    }
    Session session = this.sessions.get(player.getUniqueId());
    if (session == null
        || !session.owns(event.getView(), player.getUniqueId())
        || event.getRawSlot() != 2) {
      return;
    }
    event.setCancelled(true);
    if (!(event.getView() instanceof AnvilView anvilView)) {
      return;
    }
    makeAnvilFree(anvilView);
    String raw = anvilView.getRenameText();
    String value;
    try {
      value = switch (session.mode()) {
        case EMAIL -> AccountInput.email(raw);
        case EMAIL_CONFIRM -> AccountInput.totpCode(raw);
        case TOTP, TOTP_DISABLE -> AccountInput.password(raw);
        case TOTP_CONFIRM, TOTP_RESET -> AccountInput.totpCode(raw);
      };
    } catch (IllegalArgumentException error) {
      player.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
      return;
    }
    this.sessions.remove(player.getUniqueId());
    this.runNextTick(player, player::closeInventory);
    player.sendMessage(Component.text("正在提交账号安全设置…", NamedTextColor.GRAY));
    long requestGeneration = this.beginRequest(player);
    var future = switch (session.mode()) {
      case EMAIL -> this.client.sendEmailChallenge(player.getUniqueId(), player.getName(), value);
      case EMAIL_CONFIRM -> this.client.confirmEmail(player.getUniqueId(), player.getName(), value);
      case TOTP -> this.client.enableTotp(player.getUniqueId(), value);
      case TOTP_CONFIRM -> this.client.confirmTotp(player.getUniqueId(), value);
      case TOTP_DISABLE -> this.client.disableTotp(player.getUniqueId(), value);
      case TOTP_RESET -> this.client.rotateRecoveryCodes(player.getUniqueId(), value);
    };
    future.whenComplete((reply, error) -> player.getScheduler().run(this.plugin, ignored -> {
      if (!this.isCurrent(player, requestGeneration)) return;
      if (error != null) {
        sendFailure(player, playerError(rootMessage(error)));
        return;
      }
      if (!reply.ok()) {
        sendFailure(player, playerError(reply.message()));
        return;
      }
      if (session.mode() == Mode.EMAIL) {
        player.sendMessage(Component.text("邮箱验证码已发送", NamedTextColor.GREEN));
        this.open(player, Mode.EMAIL_CONFIRM);
        return;
      }
      if (session.mode() == Mode.EMAIL_CONFIRM) {
        player.sendMessage(Component.text("邮箱验证并绑定成功", NamedTextColor.GREEN));
        return;
      }
      if (session.mode() == Mode.TOTP) {
        player.sendMessage(Component.text("验证器密钥（点击复制）", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(reply.secret(), NamedTextColor.AQUA)
            .clickEvent(ClickEvent.copyToClipboard(reply.secret())));
        player.sendMessage(Component.text("otpauth:// 配置（点击复制）", NamedTextColor.GRAY));
        player.sendMessage(Component.text(reply.otpauthUri(), NamedTextColor.BLUE)
            .clickEvent(ClickEvent.copyToClipboard(reply.otpauthUri())));
        QrMapItem.give(player, reply.otpauthUri());
        player.sendMessage(Component.text("二维码地图已放入背包", NamedTextColor.GREEN));
        this.open(player, Mode.TOTP_CONFIRM);
        return;
      }
      if (session.mode() == Mode.TOTP_DISABLE) {
        player.sendMessage(Component.text("2FA 已关闭", NamedTextColor.GREEN));
        return;
      }
      if (session.mode() == Mode.TOTP_RESET) {
        player.sendMessage(Component.text("恢复码已轮换，旧恢复码已失效", NamedTextColor.GREEN));
        player.sendMessage(Component.text(reply.message(), NamedTextColor.AQUA)
            .clickEvent(ClickEvent.copyToClipboard(reply.message())));
        return;
      }
      player.sendMessage(Component.text("2FA 已开启", NamedTextColor.GREEN));
      player.sendMessage(Component.text(reply.message(), NamedTextColor.AQUA)
          .clickEvent(ClickEvent.copyToClipboard(reply.message())));
      player.sendMessage(Component.text(
          "点击上方内容复制，并立即保存密钥与恢复码。恢复码每个只能使用一次。",
          NamedTextColor.YELLOW));
    }, null));
  }

  @EventHandler
  public void onClose(InventoryCloseEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    this.menus.computeIfPresent(playerId, (ignored, menu) ->
        menu.owns(event.getView(), playerId) ? null : menu);
    this.sessions.computeIfPresent(playerId, (ignored, session) ->
        session.owns(event.getView(), playerId) ? null : session);
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    this.menus.remove(playerId);
    this.sessions.remove(playerId);
    this.activeRequests.remove(playerId);
  }

  void close() {
    this.closed = true;
    this.generation.incrementAndGet();
    this.menus.clear();
    this.sessions.clear();
    this.activeRequests.clear();
  }

  private static ItemStack inputItem() {
    ItemStack input = new ItemStack(Material.PAPER);
    ItemMeta meta = input.getItemMeta();
    meta.displayName(Component.text(" "));
    input.setItemMeta(meta);
    return input;
  }

  private static ItemStack confirmItem() {
    ItemStack confirm = new ItemStack(Material.LIME_DYE);
    ItemMeta meta = confirm.getItemMeta();
    meta.displayName(Component.text("\u70b9\u51fb\u786e\u8ba4", NamedTextColor.GREEN));
    confirm.setItemMeta(meta);
    return confirm;
  }

  private void runNextTick(Player player, Runnable action) {
    player.getScheduler().run(this.plugin, ignored -> {
      if (!this.closed && player.isOnline()) {
        action.run();
      }
    }, null);
  }

  static void makeAnvilFree(AnvilView view) {
    view.setRepairItemCountCost(0);
    view.setRepairCost(0);
    view.setMaximumRepairCost(Integer.MAX_VALUE);
    view.bypassEnchantmentLevelRestriction(true);
  }

  private static void sendFailure(Player player, String text) {
    Component message = Component.text(text, NamedTextColor.RED);
    player.sendMessage(message);
    player.sendActionBar(message);
  }

  static String playerError(String raw) {
    String message = raw == null ? "" : raw.trim();
    if (message.isBlank()) {
      return "\u64cd\u4f5c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002";
    }
    if (message.contains("webhook.url/secret") || message.contains("\u90ae\u4ef6\u7f51\u5173")) {
      return "\u90ae\u7bb1\u7ed1\u5b9a\u6682\u4e0d\u53ef\u7528\uff1a\u670d\u52a1\u5668\u5c1a\u672a\u914d\u7f6e\u90ae\u4ef6\u53d1\u9001\u670d\u52a1\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u3002";
    }
    return message.length() <= 240 ? message : message.substring(0, 240);
  }

  private boolean isCurrent(Player player, long requestGeneration) {
    return !this.closed && player.isOnline()
        && this.activeRequests.remove(player.getUniqueId(), requestGeneration);
  }

  private long beginRequest(Player player) {
    long requestGeneration = this.generation.incrementAndGet();
    this.activeRequests.put(player.getUniqueId(), requestGeneration);
    return requestGeneration;
  }

  private static Player player(InventoryView view) {
    return view.getPlayer() instanceof Player player ? player : null;
  }

  private static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private enum Mode {
    EMAIL("绑定邮箱", "在铁砧输入邮箱，然后点击右侧确认"),
    EMAIL_CONFIRM("确认邮箱", "输入邮件中的 6 位验证码"),
    TOTP("开启 2FA", "输入当前登录密码。内容不会发送到公共聊天，但会在铁砧输入框中可见"),
    TOTP_CONFIRM("确认 2FA", "打开验证器并输入当前 6 位动态验证码"),
    TOTP_DISABLE("关闭 2FA", "输入当前登录密码以关闭二步验证"),
    TOTP_RESET("重置恢复码", "输入当前 6 位动态验证码；旧恢复码将立即失效");

    private final String title;
    private final String hint;

    Mode(String title, String hint) {
      this.title = title;
      this.hint = hint;
    }

    private static Mode from(String value) {
      return switch (value.toLowerCase(java.util.Locale.ROOT)) {
        case "邮箱", "email" -> EMAIL;
        case "验证", "2fa", "totp" -> TOTP;
        case "关闭", "2fa-disable", "totp-disable" -> TOTP_DISABLE;
        case "重置", "2fa-reset", "totp-reset" -> TOTP_RESET;
        default -> null;
      };
    }
  }

  private enum Screen {
    MENU,
    ANVIL
  }

  private record Session(Mode mode, AccountInventoryHolder holder) {
    private boolean owns(InventoryView view, UUID playerId) {
      return this.holder.owns(view, playerId, Screen.ANVIL);
    }
  }

  private record MenuSession(AccountInventoryHolder holder) {
    private boolean owns(InventoryView view, UUID playerId) {
      return this.holder.owns(view, playerId, Screen.MENU);
    }
  }

  private static final class AccountInventoryHolder implements InventoryHolder {
    private final UUID playerId;
    private final Screen screen;
    private Inventory inventory;

    private AccountInventoryHolder(UUID playerId, Screen screen) {
      this.playerId = playerId;
      this.screen = screen;
    }

    private void attach(Inventory inventory) {
      if (this.inventory != null) {
        throw new IllegalStateException("Account inventory holder is already attached");
      }
      this.inventory = inventory;
    }

    private boolean owns(InventoryView view, UUID playerId, Screen screen) {
      return this.playerId.equals(playerId)
          && this.screen == screen
          && view.getTopInventory().getHolder() == this;
    }

    @Override
    public Inventory getInventory() {
      return this.inventory;
    }
  }
}
