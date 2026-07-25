package io.github.addxiaoyi.starx.velocity.integration;

import com.velocitypowered.api.proxy.Player;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.logging.Level;

public final class LuckPermsContextModule implements VelocityModule {

  private static final String PROVIDER_CLASS = "net.luckperms.api.LuckPermsProvider";
  private static final String CALCULATOR_CLASS = "net.luckperms.api.context.ContextCalculator";
  private static final String CONSUMER_CLASS = "net.luckperms.api.context.ContextConsumer";
  private static final String MANAGER_CLASS = "net.luckperms.api.context.ContextManager";
  private static final String CONTEXT_SET_CLASS = "net.luckperms.api.context.ImmutableContextSet";
  private static final String CONTEXT_BUILDER_CLASS =
      "net.luckperms.api.context.ImmutableContextSet$Builder";

  private final StarxVelocityPlugin plugin;
  private final JdbcBindingRepository bindings;
  private Object contextManager;
  private Object calculator;
  private Method unregister;

  public LuckPermsContextModule(
      StarxVelocityPlugin plugin,
      JdbcBindingRepository bindings) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
  }

  @Override
  public String name() {
    return "starx.integrations.luckperms";
  }

  @Override
  public void onEnable() {
    if (this.plugin.proxy().getPluginManager().getPlugin("luckperms").isEmpty()) {
      this.plugin.logger().info("未安装 LuckPerms，跳过权限上下文增强");
      return;
    }
    try {
      Class<?> calculatorType = Class.forName(CALCULATOR_CLASS);
      Object luckPerms = Class.forName(PROVIDER_CLASS).getMethod("get").invoke(null);
      this.contextManager = luckPerms.getClass().getMethod("getContextManager").invoke(luckPerms);
      this.calculator = Proxy.newProxyInstance(
          calculatorType.getClassLoader(),
          new Class<?>[]{calculatorType},
          new CalculatorHandler());
      Class<?> managerType = Class.forName(MANAGER_CLASS);
      managerType.getMethod("registerCalculator", calculatorType)
          .invoke(this.contextManager, this.calculator);
      this.unregister = managerType.getMethod("unregisterCalculator", calculatorType);
      this.plugin.logger().info("已解锁 LuckPerms：qq-bound / discord-bound 权限上下文");
    } catch (ReflectiveOperationException error) {
      throw integrationFailure("LuckPerms 已安装，但 API 不兼容", error);
    }
  }

  @Override
  public void onDisable() {
    Object currentManager = this.contextManager;
    Object currentCalculator = this.calculator;
    Method currentUnregister = this.unregister;
    this.contextManager = null;
    this.calculator = null;
    this.unregister = null;
    if (currentManager == null || currentCalculator == null || currentUnregister == null) {
      return;
    }
    try {
      currentUnregister.invoke(currentManager, currentCalculator);
    } catch (IllegalAccessException | InvocationTargetException error) {
      throw integrationFailure("无法注销 LuckPerms 上下文", error);
    }
  }

  private final class CalculatorHandler implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return switch (method.getName()) {
        case "calculate" -> calculate(args);
        case "estimatePotentialContexts" -> potentialContexts();
        case "toString" -> "StarXBindingContextCalculator";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> null;
      };
    }

    private Object calculate(Object[] args) throws ReflectiveOperationException {
      if (args == null || args.length != 2 || !(args[0] instanceof Player player)) {
        throw new IllegalArgumentException("LuckPerms calculate arguments are incompatible");
      }
      BindingContextValues values;
      try {
        values = bindings.findByPlayer(player.getUniqueId())
            .map(BindingContextValues::from)
            .orElseGet(BindingContextValues::empty);
      } catch (RuntimeException error) {
        plugin.logger().log(
            Level.WARNING,
            "无法读取玩家 " + player.getUsername() + " 的绑定权限上下文",
            error);
        values = BindingContextValues.empty();
      }
      Method accept = Class.forName(CONSUMER_CLASS)
          .getMethod("accept", String.class, String.class);
      for (var entry : values.asMap().entrySet()) {
        accept.invoke(args[1], entry.getKey(), entry.getValue());
      }
      return null;
    }

    private Object potentialContexts() throws ReflectiveOperationException {
      Object builder = Class.forName(CONTEXT_SET_CLASS).getMethod("builder").invoke(null);
      Class<?> builderType = Class.forName(CONTEXT_BUILDER_CLASS);
      Method add = builderType.getMethod("add", String.class, String.class);
      for (String key : new String[]{"qq-bound", "discord-bound"}) {
        add.invoke(builder, key, "true");
        add.invoke(builder, key, "false");
      }
      return builderType.getMethod("build").invoke(builder);
    }
  }

  private static IllegalStateException integrationFailure(
      String message,
      ReflectiveOperationException error) {
    Throwable cause = error instanceof InvocationTargetException invocation
        && invocation.getCause() != null
        ? invocation.getCause()
        : error;
    return new IllegalStateException(message, cause);
  }
}
