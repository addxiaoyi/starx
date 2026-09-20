# StarX 扩展系统 - 完整文档

## 📋 概述

StarX 扩展系统提供了一个强大的模块化架构，允许开发者创建自定义的扩展来增强 StarX 服务器的功能。本文档涵盖了所有新增的扩展系统组件和功能。

## 🎯 核心功能

### 1. 扩展基础架构

#### StarxExtension
基础扩展接口，所有扩展都需要实现的核心接口。

```java
public interface StarxExtension {
    String getId();
    String getName();
    String getVersion();
    String getDescription();
    List<String> getAuthors();
    void onEnable();
    void onDisable();
    boolean isEnabled();
}
```

#### AbstractStarxExtension
抽象扩展类，提供了扩展的基本实现和生命周期管理。

**特性：**
- 自动注册命令
- 配置管理
- 事件总线集成
- 依赖检查
- 生命周期管理

### 2. 扩展管理

#### ExtensionManager
核心扩展管理器，负责扩展的加载、启用、禁用和卸载。

**主要功能：**
- 扩展加载和卸载
- 生命周期管理
- 扩展状态跟踪
- 事件分发
- 依赖管理

**用法示例：**
```java
ExtensionManager manager = ExtensionManager.getInstance();

// 加载扩展
manager.loadExtension(new MyExtension());

// 启用扩展
manager.enableExtension("my-extension");

// 禁用扩展
manager.disableExtension("my-extension");

// 获取扩展
Optional<StarxExtension> extension = manager.getExtension("my-extension");
```

#### ExtensionLoader
扩展加载器，负责从不同来源加载扩展。

**支持的加载方式：**
- 类路径加载
- JAR文件加载
- 目录扫描加载
- 动态加载

**用法示例：**
```java
ExtensionLoader loader = new ExtensionLoader();

// 从目录加载扩展
List<StarxExtension> extensions = loader.loadFromDirectory(
    Paths.get("extensions"),
    ExtensionManager.getInstance()
);

// 从JAR文件加载
loader.loadFromJar(Paths.get("my-extension.jar"), manager);
```

### 3. 扩展注册表

#### ExtensionRegistry
扩展注册表，提供扩展的注册和发现机制。

**特性：**
- 扩展注册和注销
- 扩展发现
- 扩展分类
- 扩展过滤

**用法示例：**
```java
ExtensionRegistry registry = ExtensionRegistry.getInstance();

// 注册扩展
registry.registerExtension(new MyExtension());

// 发现扩展
List<StarxExtension> chatExtensions = registry.getExtensionsByCategory("chat");

// 获取扩展
Optional<StarxExtension> extension = registry.getExtension("my-extension");
```

### 4. 扩展配置

#### ExtensionConfig
扩展配置接口，提供统一的配置管理。

**特性：**
- 多格式配置支持 (YAML, JSON, HOCON)
- 自动配置重载
- 配置验证
- 默认值支持

**用法示例：**
```java
public class MyExtensionConfig implements ExtensionConfig {
    private boolean enabled = true;
    private String message = "Hello, World!";
    private List<String> allowedCommands = List.of("help", "info");
    
    // Getters and setters
}

// 在扩展中使用
MyExtensionConfig config = getConfig();
if (config.isEnabled()) {
    System.out.println(config.getMessage());
}
```

#### ExtensionConfigurationHelper
配置助手，提供常用的配置操作。

**特性：**
- 类型安全的配置访问
- 范围限制
- 枚举支持
- 验证支持

**用法示例：**
```java
// 获取配置值
int port = helper.getInt(config, "port", 8080, 1, 65535);
String name = helper.getString(config, "name", "default");
boolean enabled = helper.getBoolean(config, "enabled", true);

// 获取枚举值
MyEnum value = helper.getEnum(config, "mode", MyEnum.DEFAULT, MyEnum.values());

// 验证配置
ValidationResult result = helper.validateConfig(config);
if (!result.isValid()) {
    result.errors().forEach(System.err::println);
}
```

### 5. 扩展事件

#### ExtensionEventBus
扩展事件总线，提供事件的发布和订阅机制。

**特性：**
- 异步事件处理
- 事件优先级
- 事件取消
- 事件过滤

**用法示例：**
```java
// 注册事件监听器
@EventHandler(priority = EventPriority.HIGH)
public void onPlayerJoin(PlayerJoinEvent event) {
    if (event.getPlayer().isBanned()) {
        event.setCancelled(true);
    }
}

// 发布事件
ExtensionEventBus bus = ExtensionEventBus.getInstance();
PlayerJoinEvent event = new PlayerJoinEvent(player);
bus.post(event);

// 异步发布事件
bus.postAsync(event);
```

#### ExtensionEvent
基础事件类，所有自定义事件都应该继承。

**特性：**
- 事件取消
- 事件优先级
- 事件源
- 事件时间戳

### 6. 扩展命令

#### ExtensionCommand
扩展命令接口，提供命令的注册和执行机制。

**特性：**
- 命令注册
- 命令权限
- 命令参数
- 命令别名
- 命令帮助

**用法示例：**
```java
@Command("mycommand")
@Description("My custom command")
@Permission("myextension.command")
public class MyCommand implements ExtensionCommand {
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage("Hello from my command!");
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of("option1", "option2");
    }
}
```

#### ExtensionCommandRegistrar
命令注册器，负责命令的注册和管理。

**特性：**
- 命令注册
- 命令卸载
- 命令发现
- 命令权限管理

**用法示例：**
```java
ExtensionCommandRegistrar registrar = ExtensionCommandRegistrar.getInstance();

// 注册命令
registrar.registerCommand(new MyCommand());

// 卸载命令
registrar.unregisterCommand("mycommand");

// 获取命令
Optional<ExtensionCommand> command = registrar.getCommand("mycommand");
```

### 7. 扩展依赖管理

#### ExtensionDependencyManager
扩展依赖管理器，管理扩展之间的依赖关系。

**特性：**
- 依赖注册
- 依赖检查
- 循环依赖检测
- 版本兼容性检查
- 可选依赖支持

**用法示例：**
```java
ExtensionDependencyManager manager = ExtensionDependencyManager.getInstance();

// 注册依赖
manager.registerDependencies("my-extension", List.of(
    new ExtensionDependency("required-extension", ">=1.0.0", false, "compile", Map.of()),
    new ExtensionDependency("optional-extension", ">=2.0.0", true, "compile", Map.of())
));

// 检查依赖
boolean dependenciesSatisfied = manager.checkDependencies("my-extension");

// 检查版本兼容性
DependencyVersionResult result = manager.checkVersionCompatibility("my-extension");
if (result.hasConflicts()) {
    result.conflicts().forEach(conflict -> 
        System.err.println("Conflict: " + conflict.message()));
}
```

### 8. 扩展兼容性

#### ExtensionCompatibilityManager
扩展兼容性管理器，检查扩展与服务器版本的兼容性。

**特性：**
- 版本兼容性检查
- API兼容性检查
- 平台兼容性检查
- 兼容性报告生成

**用法示例：**
```java
ExtensionCompatibilityManager manager = ExtensionCompatibilityManager.getInstance();

// 检查兼容性
CompatibilityResult result = manager.checkCompatibility("my-extension");
if (result.isCompatible()) {
    System.out.println("Extension is compatible!");
} else {
    result.getIssues().forEach(issue -> 
        System.err.println("Issue: " + issue.getDescription()));
}

// 获取兼容性报告
CompatibilityReport report = manager.generateCompatibilityReport();
report.getExtensions().forEach((id, result) -> {
    System.out.println(id + ": " + (result.isCompatible() ? "✓" : "✗"));
});
```

### 9. 扩展热重载

#### ExtensionHotReloadManager
扩展热重载管理器，支持在不重启服务器的情况下重载扩展。

**特性：**
- 热重载
- 状态保存
- 重载回调
- 错误处理

**用法示例：**
```java
ExtensionHotReloadManager manager = ExtensionHotReloadManager.getInstance();

// 热重载扩展
ReloadResult result = manager.hotReload("my-extension");
if (result.isSuccessful()) {
    System.out.println("Extension reloaded successfully!");
} else {
    System.err.println("Reload failed: " + result.getErrorMessage());
}

// 重载所有扩展
ReloadResult allResult = manager.hotReloadAll();

// 注册重载监听器
manager.addReloadListener((extensionId, result) -> {
    if (result.isSuccessful()) {
        System.out.println("Extension " + extensionId + " reloaded!");
    }
});
```

### 10. 扩展依赖检查

#### ExtensionDependencyChecker
扩展依赖检查器，提供详细的依赖检查功能。

**特性：**
- 依赖可用性检查
- 版本范围验证
- 依赖冲突检测
- 缺失依赖报告

**用法示例：**
```java
ExtensionDependencyChecker checker = ExtensionDependencyChecker.getInstance();

// 检查依赖
DependencyCheckResult result = checker.checkDependencies("my-extension");
if (result.isSatisfied()) {
    System.out.println("All dependencies are satisfied!");
} else {
    result.getMissingDependencies().forEach(dep -> 
        System.err.println("Missing: " + dep.dependencyId()));
    
    result.getVersionConflicts().forEach(conflict -> 
        System.err.println("Conflict: " + conflict.message()));
}

// 检查特定依赖
boolean hasDependency = checker.hasDependency("my-extension", "required-extension");
```

## 🎨 标签页系统 (TabList)

### TabList API

StarX 提供了强大的标签页自定义功能，支持动画、图片嵌入等高级特性。

#### TabList
基础标签页接口。

```java
public interface TabList {
    String getHeader();
    String getFooter();
    void setHeader(String header);
    void setFooter(String footer);
    void update();
    void reset();
}
```

#### DefaultTabList
默认标签页实现，提供基础的标签页功能。

**特性：**
- 头部和尾部自定义
- 占位符支持
- 动态更新
- 重置功能

**用法示例：**
```java
DefaultTabList tabList = new DefaultTabList();
tabList.setHeader("&6Welcome to &eMyServer&6!");
tabList.setFooter("&7Players: &a%player_count%");
tabList.update();
```

### 动画支持

#### TabAnimation
标签页动画基础接口。

```java
public interface TabAnimation {
    String getId();
    String getName();
    String animate(String text, long timestamp);
    void start();
    void stop();
    boolean isRunning();
}
```

#### FlowingAnimation
流动动画，让文字像流水一样滚动。

**特性：**
- 自定义滚动速度
- 自定义滚动方向
- 循环滚动
- 可配置的滚动范围

**用法示例：**
```java
FlowingAnimation animation = new FlowingAnimation(
    "Hello, World! ",  // 要滚动的文字
    2,                 // 滚动速度 (字符/秒)
    FlowingAnimation.Direction.LEFT_TO_RIGHT  // 滚动方向
);

animation.start();
String animatedText = animation.animate("", System.currentTimeMillis());
animation.stop();
```

#### PulseAnimation
脉冲动画，让文字产生闪烁效果。

**特性：**
- 自定义闪烁频率
- 自定义颜色变化
- 多种闪烁模式

**用法示例：**
```java
PulseAnimation animation = new PulseAnimation(
    "Hello, World!",  // 要闪烁的文字
    2.0,              // 闪烁频率 (次/秒)
    List.of("&c", "&e", "&a")  // 颜色序列
);

animation.start();
String animatedText = animation.animate("", System.currentTimeMillis());
```

#### ScrollingTextAnimation
滚动文字动画，让长文字在有限空间内滚动显示。

**特性：**
- 自定义滚动速度
- 自定义滚动方向
- 循环滚动
- 可配置的显示宽度

**用法示例：**
```java
ScrollingTextAnimation animation = new ScrollingTextAnimation(
    "Welcome to our amazing server! ",  // 要滚动的文字
    3,                                 // 滚动速度 (字符/秒)
    40                                 // 显示宽度
);

animation.start();
String animatedText = animation.animate("", System.currentTimeMillis());
```

#### GradientAnimation
渐变动画，让文字颜色产生渐变效果。

**特性：**
- 自定义渐变颜色
- 自定义渐变速度
- 多种渐变模式

**用法示例：**
```java
GradientAnimation animation = new GradientAnimation(
    "Hello, World!",  // 要渐变的文字
    List.of("&c", "&6", "&e", "&a"),  // 渐变颜色
    1.0,              // 渐变速度
    GradientAnimation.Mode.LEFT_TO_RIGHT  // 渐变模式
);

animation.start();
String animatedText = animation.animate("", System.currentTimeMillis());
```

### 图片支持

#### TabImage
标签页图片接口，支持在标签页中嵌入图片。

```java
public interface TabImage {
    String getId();
    String getName();
    String getImageData();
    int getWidth();
    int getHeight();
    TabImageStyle getStyle();
}
```

#### TabImageStyle
图片样式，定义图片的显示方式。

**特性：**
- 图片对齐方式
- 图片缩放模式
- 图片透明度
- 图片边框

**用法示例：**
```java
TabImageStyle style = new TabImageStyle(
    TabImageStyle.Alignment.CENTER,  // 对齐方式
    TabImageStyle.ScaleMode.FIT,     // 缩放模式
    1.0,                             // 透明度
    TabImageStyle.Border.NONE        // 边框
);

// 创建图片
TabImage image = new Base64TabImage(
    "my-image",
    "logo",
    base64ImageData,
    16,
    16,
    style
);
```

#### Base64TabImage
Base64编码的图片实现。

**用法示例：**
```java
// 从文件加载Base64图片
String base64Data = Files.readString(Paths.get("logo.png"));
TabImage image = new Base64TabImage(
    "server-logo",
    "Server Logo",
    base64Data,
    32,
    32,
    new TabImageStyle()
);
```

#### UrlTabImage
URL图片实现，从网络URL加载图片。

**用法示例：**
```java
TabImage image = new UrlTabImage(
    "web-logo",
    "Web Logo", 
    "https://example.com/logo.png",
    32,
    32,
    new TabImageStyle()
);
```

### 标签页管理

#### TabListRegistry
标签页注册表，管理所有标签页配置。

**特性：**
- 标签页注册
- 标签页发现
- 标签页分类
- 标签页过滤

**用法示例：**
```java
TabListRegistry registry = TabListRegistry.getInstance();

// 注册标签页
registry.registerTabList("default", new DefaultTabList());

// 获取标签页
Optional<TabList> tabList = registry.getTabList("default");

// 获取所有标签页
List<TabList> allTabLists = registry.getAllTabLists();
```

#### TabListManager
标签页管理器，负责标签页的创建、更新和管理。

**特性：**
- 标签页创建
- 标签页更新
- 标签页删除
- 标签页切换

**用法示例：**
```java
TabListManager manager = TabListManager.getInstance();

// 创建标签页
TabList tabList = manager.createTabList("custom");

// 设置标签页
manager.setTabList("custom", tabList);

// 更新标签页
manager.updateTabList("custom");

// 删除标签页
manager.deleteTabList("custom");
```

### Velocity标签页模块

#### TabListModule
Velocity平台的标签页模块。

**特性：**
- Velocity原生集成
- 多服务器支持
- 玩家特定标签页
- 服务器切换时自动更新

**配置示例 (tab-list.yml):**
```yaml
enabled: true
update-interval: 20
default-tab-list: "default"

animations:
  welcome:
    type: "flowing"
    text: "Welcome to our server! "
    speed: 2
    direction: "left_to_right"
  
  server-info:
    type: "scrolling"
    text: "Players: %player_count% | TPS: %tps% | Uptime: %uptime% "
    speed: 3
    width: 40

images:
  logo:
    type: "base64"
    data: "..."
    width: 16
    height: 16
    style:
      alignment: "center"
      scale-mode: "fit"

tab-lists:
  default:
    header: "&6%animation:welcome%&r"
    footer: "&7%animation:server-info%&r\n&8%image:logo%"
    
  vip:
    header: "&6Welcome, &e%player_name%&6!"
    footer: "&7You are a VIP player!"
```

**用法示例：**
```java
// 在Velocity插件中启用标签页模块
TabListModule module = new TabListModule();
module.enable();

// 获取模块配置
TabListConfig config = module.getConfig();

// 更新所有玩家的标签页
module.updateAllPlayers();

// 为特定玩家更新标签页
module.updatePlayer(player);
```

## 🔧 扩展开发指南

### 1. 创建扩展

**基本步骤：**

1. **创建扩展类**
```java
public class MyExtension extends AbstractStarxExtension {
    
    @Override
    public String getId() {
        return "my-extension";
    }
    
    @Override
    public String getName() {
        return "My Extension";
    }
    
    @Override
    public String getVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getDescription() {
        return "My custom extension for StarX";
    }
    
    @Override
    public List<String> getAuthors() {
        return List.of("YourName");
    }
    
    @Override
    public void onEnable() {
        getLogger().info("MyExtension has been enabled!");
        
        // 注册命令
        registerCommand(new MyCommand());
        
        // 注册事件监听器
        registerEventListener(new MyEventListener());
    }
    
    @Override
    public void onDisable() {
        getLogger().info("MyExtension has been disabled!");
    }
}
```

2. **创建扩展配置**
```java
public class MyExtensionConfig implements ExtensionConfig {
    private boolean enabled = true;
    private String message = "Hello, World!";
    private int cooldown = 5;
    
    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public int getCooldown() { return cooldown; }
    public void setCooldown(int cooldown) { this.cooldown = cooldown; }
}
```

3. **创建扩展插件类**
```java
public class MyExtensionPlugin implements StarxExtensionPlugin {
    
    @Override
    public StarxExtension createExtension() {
        return new MyExtension();
    }
    
    @Override
    public Class<? extends ExtensionConfig> getConfigClass() {
        return MyExtensionConfig.class;
    }
}
```

4. **创建 plugin.yml**
```yaml
id: my-extension
name: My Extension
version: 1.0.0
description: My custom extension for StarX
authors:
  - YourName
main: com.example.MyExtensionPlugin
dependencies:
  - required-extension: ">=1.0.0"
optional-dependencies:
  - optional-extension: ">=2.0.0"
```

### 2. 扩展生命周期

**加载阶段：**
1. `onLoad()` - 扩展被加载时调用
2. `onEnable()` - 扩展被启用时调用
3. `onDisable()` - 扩展被禁用时调用
4. `onUnload()` - 扩展被卸载时调用

**建议的操作时机：**
- `onLoad()`: 注册命令、事件监听器、配置加载
- `onEnable()`: 启动任务、连接数据库、初始化缓存
- `onDisable()`: 停止任务、关闭连接、清理资源
- `onUnload()`: 卸载所有注册的内容

### 3. 扩展依赖管理

**声明依赖：**
```yaml
# plugin.yml
dependencies:
  - core-extension: ">=1.0.0"  # 强制依赖
  - database-extension: ">=2.0.0"  # 强制依赖

optional-dependencies:
  - metrics-extension: ">=1.5.0"  # 可选依赖
  - analytics-extension: ">=1.0.0"  # 可选依赖
```

**检查依赖：**
```java
@Override
public void onEnable() {
    ExtensionDependencyManager manager = ExtensionDependencyManager.getInstance();
    
    if (!manager.checkDependencies(getId())) {
        getLogger().error("Dependencies are not satisfied!");
        setEnabled(false);
        return;
    }
    
    // 扩展逻辑
}
```

### 4. 扩展配置管理

**加载配置：**
```java
@Override
public void onEnable() {
    // 自动加载配置
    MyExtensionConfig config = getConfig();
    
    // 手动加载配置
    MyExtensionConfig customConfig = loadConfig("custom-config.yml", MyExtensionConfig.class);
    
    // 保存配置
    saveConfig(config);
    
    // 重载配置
    reloadConfig();
}
```

**配置验证：**
```java
public class MyExtensionConfigValidator implements ConfigValidator<MyExtensionConfig> {
    
    @Override
    public ValidationResult validate(MyExtensionConfig config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (config.getCooldown() < 0) {
            errors.add("Cooldown cannot be negative");
        }
        
        if (config.getMessage() == null || config.getMessage().isEmpty()) {
            warnings.add("Message is empty, using default");
            config.setMessage("Hello, World!");
        }
        
        return new ValidationResult(
            errors.isEmpty(),
            errors,
            warnings,
            Map.of()
        );
    }
}
```

### 5. 扩展事件处理

**注册事件监听器：**
```java
@Override
public void onEnable() {
    // 注册事件监听器
    registerEventListener(new MyEventListener());
}

public class MyEventListener {
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.sendMessage("Welcome to the server!");
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(PlayerChatEvent event) {
        if (event.getMessage().contains("bad word")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("You cannot say that!");
        }
    }
    
    @EventHandler
    public void onExtensionEnable(ExtensionEnableEvent event) {
        if (event.getExtension().getId().equals("required-extension")) {
            getLogger().info("Required extension enabled!");
        }
    }
}
```

**发布自定义事件：**
```java
public class CustomEvent implements ExtensionEvent {
    private final String message;
    private boolean cancelled = false;
    
    public CustomEvent(String message) {
        this.message = message;
    }
    
    public String getMessage() { return message; }
    
    @Override
    public boolean isCancelled() { return cancelled; }
    
    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}

// 发布事件
ExtensionEventBus bus = ExtensionEventBus.getInstance();
CustomEvent event = new CustomEvent("Hello, World!");
bus.post(event);

// 异步发布事件
bus.postAsync(event);
```

### 6. 扩展命令处理

**创建命令：**
```java
@Command("mycommand")
@Aliases({"mc", "mycmd"})
@Description("My custom command")
@Permission("myextension.command")
@Usage("/mycommand <subcommand> [args]")
public class MyCommand implements ExtensionCommand {
    
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /mycommand <subcommand>");
            return;
        }
        
        switch (args[0].toLowerCase()) {
            case "help":
                showHelp(sender);
                break;
            case "info":
                showInfo(sender);
                break;
            default:
                sender.sendMessage("Unknown subcommand");
        }
    }
    
    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("help", "info");
        }
        return List.of();
    }
    
    private void showHelp(CommandSender sender) {
        sender.sendMessage("&6MyCommand Help:");
        sender.sendMessage("&7/mycommand help - Show this help");
        sender.sendMessage("&7/mycommand info - Show extension info");
    }
    
    private void showInfo(CommandSender sender) {
        sender.sendMessage("&6MyExtension v1.0.0");
        sender.sendMessage("&7Author: YourName");
    }
}
```

**注册命令：**
```java
@Override
public void onEnable() {
    // 注册命令
    registerCommand(new MyCommand());
    
    // 或者使用命令注册器
    ExtensionCommandRegistrar registrar = ExtensionCommandRegistrar.getInstance();
    registrar.registerCommand(new MyCommand());
}
```

### 7. 扩展热重载

**支持热重载：**
```java
public class MyExtension extends AbstractStarxExtension {
    
    @Override
    public boolean supportsHotReload() {
        return true;  // 支持热重载
    }
    
    @Override
    public void onHotReload() {
        // 热重载时执行的操作
        getLogger().info("MyExtension hot reloaded!");
        
        // 重新加载配置
        reloadConfig();
        
        // 重新注册事件监听器
        reregisterEventListeners();
    }
    
    @Override
    public CompletableFuture<ReloadState> saveState() {
        // 保存状态
        return CompletableFuture.completedFuture(new ReloadState(Map.of()));
    }
    
    @Override
    public CompletableFuture<Void> restoreState(ReloadState state) {
        // 恢复状态
        return CompletableFuture.completedFuture(null);
    }
}
```

### 8. 扩展兼容性检查

**声明兼容性：**
```java
public class MyExtension extends AbstractStarxExtension {
    
    @Override
    public PlatformCompatibility getPlatformCompatibility() {
        return new PlatformCompatibility(
            Map.of(
                PlatformType.VELOCITY, new VersionRange(">=3.0.0"),
                PlatformType.BUNGEE, new VersionRange(">=1.16.0")
            )
        );
    }
    
    @Override
    public ApiCompatibility getApiCompatibility() {
        return new ApiCompatibility(
            Map.of(
                "starx-api", new VersionRange(">=1.0.0"),
                "adventure", new VersionRange(">=4.0.0")
            )
        );
    }
}
```

**检查兼容性：**
```java
@Override
public void onEnable() {
    ExtensionCompatibilityManager manager = ExtensionCompatibilityManager.getInstance();
    
    CompatibilityResult result = manager.checkCompatibility(getId());
    if (!result.isCompatible()) {
        getLogger().error("Extension is not compatible with current environment!");
        result.getIssues().forEach(issue -> 
            getLogger().error("Issue: " + issue.getDescription()));
        setEnabled(false);
        return;
    }
}
```

## 📊 扩展最佳实践

### 1. 扩展设计原则

**单一职责原则：**
- 每个扩展应该只做一件事
- 避免创建过于复杂的扩展
- 可以将大功能拆分为多个小扩展

**模块化设计：**
- 将功能分为多个模块
- 每个模块有明确的职责
- 模块之间通过接口通信

**依赖最小化：**
- 尽量减少对其他扩展的依赖
- 使用可选依赖而不是强制依赖
- 提供回退机制

### 2. 性能优化

**异步操作：**
```java
// 使用异步任务
getServer().getScheduler().buildTask(this, () -> {
    // 耗时操作
    heavyOperation();
}).schedule();

// 使用异步事件
bus.postAsync(event);
```

**缓存机制：**
```java
private final Cache<String, Object> cache = Caffeine.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build();

public Object getData(String key) {
    return cache.get(key, k -> loadData(k));
}
```

**批量操作：**
```java
// 批量更新玩家标签页
List<Player> players = getServer().getAllPlayers();
TabListManager manager = TabListManager.getInstance();

players.forEach(player -> manager.updatePlayer(player));
```

### 3. 错误处理

**异常处理：**
```java
@Override
public void onEnable() {
    try {
        // 初始化代码
        initialize();
    } catch (Exception e) {
        getLogger().error("Failed to initialize extension", e);
        setEnabled(false);
    }
}

public void initialize() throws Exception {
    // 可能抛出异常的代码
}
```

**优雅降级：**
```java
public void someFeature() {
    try {
        // 尝试使用高级功能
        advancedFeature();
    } catch (UnsupportedOperationException e) {
        // 降级到基础功能
        basicFeature();
    }
}
```

### 4. 日志记录

**日志级别：**
```java
// 调试信息
getLogger().debug("Debug information");

// 一般信息
getLogger().info("Extension enabled");

// 警告
getLogger().warn("Something unexpected happened");

// 错误
getLogger().error("Something went wrong", exception);
```

**结构化日志：**
```java
getLogger().info("Player {} joined the server", player.getUsername());
getLogger().error("Failed to load config: {}", exception.getMessage(), exception);
```

### 5. 配置管理

**配置迁移：**
```java
public class MyExtensionConfig implements ExtensionConfig {
    @Deprecated
    private String oldField;
    
    private String newField;
    
    // 迁移逻辑
    @PostLoad
    public void migrate() {
        if (oldField != null && newField == null) {
            newField = oldField;
            oldField = null;
        }
    }
}
```

**配置验证：**
```java
public class MyExtensionConfigValidator implements ConfigValidator<MyExtensionConfig> {
    
    @Override
    public ValidationResult validate(MyExtensionConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.getPort() < 1 || config.getPort() > 65535) {
            errors.add("Port must be between 1 and 65535");
        }
        
        if (config.getTimeout() < 0) {
            errors.add("Timeout cannot be negative");
        }
        
        return new ValidationResult(!errors.isEmpty(), errors, List.of(), Map.of());
    }
}
```

### 6. 扩展文档

**提供良好的文档：**
- README.md - 扩展说明
- CHANGELOG.md - 更新日志
- LICENSE - 许可证
- docs/ - 详细文档

**文档内容：**
- 功能描述
- 安装说明
- 配置说明
- 使用示例
- API文档
- 常见问题

## 🔍 扩展调试

### 1. 调试模式

**启用调试模式：**
```java
public class MyExtension extends AbstractStarxExtension {
    
    @Override
    public void onEnable() {
        if (isDebugEnabled()) {
            getLogger().info("Debug mode enabled!");
            enableDebugFeatures();
        }
    }
}
```

### 2. 日志调试

**增加日志详细度：**
```java
// 在扩展配置中
public class MyExtensionConfig implements ExtensionConfig {
    private boolean debug = false;
    private LogLevel logLevel = LogLevel.INFO;
    
    // Getters and setters
}

// 在扩展中
@Override
public void onEnable() {
    MyExtensionConfig config = getConfig();
    getLogger().setLevel(config.getLogLevel());
    
    if (config.isDebug()) {
        getLogger().debug("Debug mode enabled");
    }
}
```

### 3. 扩展检查

**检查扩展状态：**
```java
ExtensionManager manager = ExtensionManager.getInstance();

// 检查扩展是否启用
boolean isEnabled = manager.isExtensionEnabled("my-extension");

// 获取扩展状态
ExtensionStatus status = manager.getExtensionStatus("my-extension");

// 获取扩展信息
Optional<StarxExtension> extension = manager.getExtension("my-extension");
```

**检查依赖状态：**
```java
ExtensionDependencyManager manager = ExtensionDependencyManager.getInstance();

// 检查依赖是否满足
boolean dependenciesSatisfied = manager.checkDependencies("my-extension");

// 获取依赖信息
List<ExtensionDependency> dependencies = manager.getDependencies("my-extension");

// 检查版本兼容性
DependencyVersionResult result = manager.checkVersionCompatibility("my-extension");
```

## 📦 扩展打包和发布

### 1. 打包扩展

**Gradle配置：**
```gradle
plugins {
    id 'java'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

repositories {
    mavenCentral()
    maven { url 'https://repo.elytrium.com/releases' }
}

dependencies {
    compileOnly 'io.github.addxiaoyi.starx:starx-api:0.4.9'
    
    // 其他依赖
}

shadowJar {
    archiveFileName = "my-extension-${version}-all.jar"
    
    relocate 'com.google.common', 'my.extension.shaded.com.google.common'
}
```

**构建扩展：**
```bash
./gradlew shadowJar
```

### 2. 发布扩展

**发布到Maven仓库：**
```gradle
publishing {
    repositories {
        maven {
            name = 'GitHub Packages'
            url = version.endsWith('SNAPSHOT') ? 
                uri('https://maven.pkg.github.com/YourUsername/YourRepo') :
                uri('https://maven.pkg.github.com/YourUsername/YourRepo/releases')
            credentials {
                username = System.getenv('GITHUB_ACTOR')
                password = System.getenv('GITHUB_TOKEN')
            }
        }
    }
    
    publications {
        mavenJava(MavenPublication) {
            from components.java
        }
    }
}
```

**发布命令：**
```bash
./gradlew publish
```

### 3. 扩展分发

**分发渠道：**
- GitHub Releases
- SpigotMC Resources
- Hangar
- 自建仓库
- 直接分发JAR文件

**版本管理：**
- 遵循语义化版本 (Semantic Versioning)
- 保持向后兼容性
- 提供更新日志
- 提供升级指南

## 🎯 标签页自定义示例

### 1. 简单标签页

**配置文件：**
```yaml
tab-lists:
  simple:
    header: "&6Welcome to &eMyServer&6!"
    footer: "&7Players: &a%player_count% &7| TPS: &a%tps%"
```

**代码实现：**
```java
TabList simpleTabList = new DefaultTabList();
simpleTabList.setHeader("&6Welcome to &eMyServer&6!");
simpleTabList.setFooter("&7Players: &a%player_count% &7| TPS: &a%tps%");

TabListRegistry.getInstance().registerTabList("simple", simpleTabList);
```

### 2. 动画标签页

**配置文件：**
```yaml
animations:
  welcome:
    type: "flowing"
    text: "Welcome to our amazing server! "
    speed: 2
    direction: "left_to_right"
  
  info:
    type: "scrolling"
    text: "Players: %player_count% | TPS: %tps% | Uptime: %uptime% "
    speed: 3
    width: 40

tab-lists:
  animated:
    header: "&6%animation:welcome%&r"
    footer: "&7%animation:info%&r"
```

**代码实现：**
```java
// 创建动画
FlowingAnimation welcomeAnimation = new FlowingAnimation(
    "Welcome to our amazing server! ",
    2,
    FlowingAnimation.Direction.LEFT_TO_RIGHT
);

ScrollingTextAnimation infoAnimation = new ScrollingTextAnimation(
    "Players: %player_count% | TPS: %tps% | Uptime: %uptime% ",
    3,
    40
);

// 创建标签页
TabList animatedTabList = new DefaultTabList();
animatedTabList.setHeader("&6%animation:welcome%&r");
animatedTabList.setFooter("&7%animation:info%&r");

// 注册动画
AnimationRegistry registry = AnimationRegistry.getInstance();
registry.registerAnimation("welcome", welcomeAnimation);
registry.registerAnimation("info", infoAnimation);

// 注册标签页
TabListRegistry.getInstance().registerTabList("animated", animatedTabList);
```

### 3. 图片标签页

**配置文件：**
```yaml
images:
  logo:
    type: "base64"
    data: "..."
    width: 16
    height: 16
    style:
      alignment: "center"
      scale-mode: "fit"

tab-lists:
  with-image:
    header: "&6Welcome to &eMyServer&6!"
    footer: "&7%image:logo%\n&7Players: &a%player_count%"
```

**代码实现：**
```java
// 从文件加载图片
String base64Data = Files.readString(Paths.get("logo.png"));
TabImage image = new Base64TabImage(
    "logo",
    "Server Logo",
    base64Data,
    16,
    16,
    new TabImageStyle(
        TabImageStyle.Alignment.CENTER,
        TabImageStyle.ScaleMode.FIT,
        1.0,
        TabImageStyle.Border.NONE
    )
);

// 注册图片
TabImageRegistry.getInstance().registerImage("logo", image);

// 创建标签页
TabList tabList = new DefaultTabList();
tabList.setHeader("&6Welcome to &eMyServer&6!");
tabList.setFooter("&7%image:logo%\n&7Players: &a%player_count%");

TabListRegistry.getInstance().registerTabList("with-image", tabList);
```

### 4. 玩家特定标签页

**配置文件：**
```yaml
tab-lists:
  default:
    header: "&6Welcome, &e%player_name%&6!"
    footer: "&7Players: &a%player_count%"
    
  vip:
    header: "&6Welcome, &e%player_name%&6! &7(VIP)"
    footer: "&7You are a VIP player!\n&7Players: &a%player_count%"
    
  staff:
    header: "&6Welcome, &e%player_name%&6! &7(Staff)"
    footer: "&7You are a staff member!\n&7Players: &a%player_count%"
```

**代码实现：**
```java
// 为不同权限组创建不同的标签页
TabList defaultTabList = new DefaultTabList();
defaultTabList.setHeader("&6Welcome, &e%player_name%&6!");
defaultTabList.setFooter("&7Players: &a%player_count%");

TabList vipTabList = new DefaultTabList();
vipTabList.setHeader("&6Welcome, &e%player_name%&6! &7(VIP)");
vipTabList.setFooter("&7You are a VIP player!\n&7Players: &a%player_count%");

TabList staffTabList = new DefaultTabList();
staffTabList.setHeader("&6Welcome, &e%player_name%&6! &7(Staff)");
staffTabList.setFooter("&7You are a staff member!\n&7Players: &a%player_count%");

// 注册标签页
TabListRegistry registry = TabListRegistry.getInstance();
registry.registerTabList("default", defaultTabList);
registry.registerTabList("vip", vipTabList);
registry.registerTabList("staff", staffTabList);

// 为玩家分配标签页
TabListManager manager = TabListManager.getInstance();

player.getPermissions().forEach(permission -> {
    if (permission.equals("vip")) {
        manager.setPlayerTabList(player.getUniqueId(), "vip");
    } else if (permission.equals("staff")) {
        manager.setPlayerTabList(player.getUniqueId(), "staff");
    } else {
        manager.setPlayerTabList(player.getUniqueId(), "default");
    }
});
```

### 5. 动态标签页

**代码实现：**
```java
public class DynamicTabList extends DefaultTabList {
    
    @Override
    public String getHeader() {
        // 根据服务器状态动态生成头部
        int playerCount = getServer().getOnlinePlayers().size();
        double tps = getServer().getTps();
        
        return "&6Welcome to &eMyServer&6!\n" +
               "&7Online: &a" + playerCount + " &7players";
    }
    
    @Override
    public String getFooter() {
        // 根据玩家权限动态生成尾部
        Player player = getCurrentPlayer();
        String rank = player.hasPermission("vip") ? "VIP" : "Player";
        
        return "&7Rank: &a" + rank + "\n" +
               "&7TPS: &a" + String.format("%.1f", getServer().getTps());
    }
    
    @Override
    public void update() {
        // 每次更新时重新计算内容
        super.update();
    }
}

// 注册动态标签页
TabListRegistry.getInstance().registerTabList("dynamic", new DynamicTabList());

// 定期更新
getServer().getScheduler().buildTask(this, () -> {
    TabListManager.getInstance().updateAll();
}).repeat(20, 20).schedule();
```

## 🚀 进阶功能

### 1. 扩展通信

**扩展间通信：**
```java
// 发送消息
ExtensionMessage message = new ExtensionMessage(
    "target-extension",
    "command",
    Map.of("action", "update", "data", "value")
);
ExtensionMessageBus.getInstance().sendMessage(message);

// 接收消息
@MessageHandler("command")
public void onMessage(ExtensionMessage message) {
    if (message.getAction().equals("update")) {
        String data = message.getData("data");
        // 处理消息
    }
}
```

### 2. 扩展数据存储

**数据存储：**
```java
public class MyExtension extends AbstractStarxExtension {
    private ExtensionDataStore dataStore;
    
    @Override
    public void onEnable() {
        // 初始化数据存储
        dataStore = new ExtensionDataStore(this, "my-data");
        
        // 加载数据
        dataStore.load();
    }
    
    @Override
    public void onDisable() {
        // 保存数据
        dataStore.save();
    }
    
    public void storeData(String key, Object value) {
        dataStore.set(key, value);
    }
    
    public Object getData(String key) {
        return dataStore.get(key);
    }
}
```

### 3. 扩展任务调度

**任务调度：**
```java
@Override
public void onEnable() {
    // 单次任务
    getServer().getScheduler().buildTask(this, () -> {
        getLogger().info("Task executed!");
    }).delay(20, TimeUnit.SECONDS).schedule();
    
    // 重复任务
    getServer().getScheduler().buildTask(this, () -> {
        updateTabList();
    }).repeat(20, 20).schedule();
    
    // 异步任务
    getServer().getScheduler().buildTask(this, () -> {
        heavyOperation();
    }).schedule();
}
```

### 4. 扩展网络功能

**HTTP客户端：**
```java
public class MyExtension extends AbstractStarxExtension {
    private HttpClient httpClient;
    
    @Override
    public void onEnable() {
        httpClient = HttpClient.newHttpClient();
    }
    
    public CompletableFuture<String> fetchData(String url) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .build();
        
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> response.body());
    }
}
```

### 5. 扩展数据库集成

**数据库连接：**
```java
public class MyExtension extends AbstractStarxExtension {
    private DataSource dataSource;
    
    @Override
    public void onEnable() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
        config.setUsername("user");
        config.setPassword("password");
        
        dataSource = new HikariDataSource(config);
    }
    
    @Override
    public void onDisable() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
    
    public List<String> queryData() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM my_table")) {
            
            List<String> results = new ArrayList<>();
            while (rs.next()) {
                results.add(rs.getString("column"));
            }
            return results;
        }
    }
}
```

## 📚 API参考

### 核心接口

| 接口 | 描述 |
|------|------|
| `StarxExtension` | 扩展基础接口 |
| `ExtensionManager` | 扩展管理器 |
| `ExtensionLoader` | 扩展加载器 |
| `ExtensionRegistry` | 扩展注册表 |
| `ExtensionConfig` | 扩展配置接口 |

### 扩展功能

| 类 | 描述 |
|----|------|
| `ExtensionEventBus` | 事件总线 |
| `ExtensionCommandRegistrar` | 命令注册器 |
| `ExtensionDependencyManager` | 依赖管理器 |
| `ExtensionCompatibilityManager` | 兼容性管理器 |
| `ExtensionHotReloadManager` | 热重载管理器 |
| `ExtensionDependencyChecker` | 依赖检查器 |

### 标签页API

| 类 | 描述 |
|----|------|
| `TabList` | 标签页基础接口 |
| `DefaultTabList` | 默认标签页实现 |
| `TabListRegistry` | 标签页注册表 |
| `TabListManager` | 标签页管理器 |

### 动画API

| 类 | 描述 |
|----|------|
| `TabAnimation` | 动画基础接口 |
| `FlowingAnimation` | 流动动画 |
| `PulseAnimation` | 脉冲动画 |
| `ScrollingTextAnimation` | 滚动文字动画 |
| `GradientAnimation` | 渐变动画 |

### 图片API

| 类 | 描述 |
|----|------|
| `TabImage` | 图片基础接口 |
| `TabImageStyle` | 图片样式 |
| `Base64TabImage` | Base64图片实现 |
| `UrlTabImage` | URL图片实现 |

## 🎓 示例项目

### 简单扩展示例

**MyExtension.java:**
```java
package com.example;

import io.github.addxiaoyi.starx.api.extension.AbstractStarxExtension;
import io.github.addxiaoyi.starx.api.extension.annotation.Extension;

@Extension(id = "my-extension", name = "My Extension", version = "1.0.0")
public class MyExtension extends AbstractStarxExtension {
    
    @Override
    public void onEnable() {
        getLogger().info("MyExtension has been enabled!");
        
        // 注册命令
        registerCommand(new MyCommand());
        
        // 注册事件监听器
        registerEventListener(new MyEventListener());
    }
    
    @Override
    public void onDisable() {
        getLogger().info("MyExtension has been disabled!");
    }
}
```

**MyCommand.java:**
```java
package com.example;

import io.github.addxiaoyi.starx.api.extension.command.ExtensionCommand;
import io.github.addxiaoyi.starx.api.extension.annotation.Command;
import io.github.addxiaoyi.starx.api.extension.annotation.Description;
import io.github.addxiaoyi.starx.api.extension.annotation.Permission;
import net.kyori.adventure.audience.Audience;

@Command("mycommand")
@Description("My custom command")
@Permission("myextension.command")
public class MyCommand implements ExtensionCommand {
    
    @Override
    public void execute(Audience sender, String[] args) {
        sender.sendMessage("Hello from MyExtension!");
    }
    
    @Override
    public List<String> tabComplete(Audience sender, String[] args) {
        return List.of("help", "info");
    }
}
```

**MyEventListener.java:**
```java
package com.example;

import io.github.addxiaoyi.starx.api.extension.event.ExtensionEventHandler;
import io.github.addxiaoyi.starx.api.extension.annotation.EventHandler;
import net.kyori.adventure.audience.Audience;

public class MyEventListener {
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Audience player = event.getPlayer();
        player.sendMessage("Welcome to the server!");
    }
}
```

**plugin.yml:**
```yaml
id: my-extension
name: My Extension
version: 1.0.0
description: My custom extension for StarX
authors:
  - YourName
main: com.example.MyExtension
```

### 标签页扩展示例

**TabListExtension.java:**
```java
package com.example.tablist;

import io.github.addxiaoyi.starx.api.extension.AbstractStarxExtension;
import io.github.addxiaoyi.starx.api.extension.annotation.Extension;
import io.github.addxiaoyi.starx.api.tab.*;

@Extension(id = "tab-list-extension", name = "Tab List Extension", version = "1.0.0")
public class TabListExtension extends AbstractStarxExtension {
    
    @Override
    public void onEnable() {
        getLogger().info("TabListExtension has been enabled!");
        
        // 创建动画
        FlowingAnimation welcomeAnimation = new FlowingAnimation(
            "Welcome to our server! ",
            2,
            FlowingAnimation.Direction.LEFT_TO_RIGHT
        );
        
        ScrollingTextAnimation infoAnimation = new ScrollingTextAnimation(
            "Players: %player_count% | TPS: %tps% ",
            3,
            40
        );
        
        // 注册动画
        AnimationRegistry.getInstance().registerAnimation("welcome", welcomeAnimation);
        AnimationRegistry.getInstance().registerAnimation("info", infoAnimation);
        
        // 创建标签页
        TabList tabList = new DefaultTabList();
        tabList.setHeader("&6%animation:welcome%&r");
        tabList.setFooter("&7%animation:info%&r");
        
        // 注册标签页
        TabListRegistry.getInstance().registerTabList("animated", tabList);
        
        // 设置为默认标签页
        TabListManager.getInstance().setDefaultTabList("animated");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("TabListExtension has been disabled!");
    }
}
```

## 🔧 故障排除

### 常见问题

**1. 扩展无法加载**
- 检查plugin.yml文件是否正确
- 检查主类是否正确
- 检查依赖是否满足
- 检查日志中的错误信息

**2. 扩展启用失败**
- 检查onEnable()方法中的异常
- 检查依赖扩展是否已启用
- 检查配置文件是否正确
- 检查权限是否足够

**3. 命令无法执行**
- 检查命令是否已注册
- 检查权限是否正确
- 检查命令名称是否冲突
- 检查玩家是否有执行权限

**4. 事件不触发**
- 检查事件监听器是否已注册
- 检查事件优先级是否正确
- 检查事件是否被取消
- 检查日志中的调试信息

**5. 标签页不更新**
- 检查标签页是否已注册
- 检查标签页是否已设置为活动
- 检查更新间隔是否合适
- 检查占位符是否正确

### 调试技巧

**启用调试日志：**
```java
@Override
public void onEnable() {
    getLogger().setLevel(LogLevel.DEBUG);
    // 或在配置文件中设置
}
```

**检查扩展状态：**
```java
ExtensionManager manager = ExtensionManager.getInstance();
manager.getAllExtensions().forEach(extension -> {
    getLogger().info("Extension: {} - Enabled: {}", 
        extension.getId(), extension.isEnabled());
});
```

**检查依赖状态：**
```java
ExtensionDependencyManager manager = ExtensionDependencyManager.getInstance();
manager.getDependencies("my-extension").forEach(dependency -> {
    getLogger().info("Dependency: {} - Satisfied: {}", 
        dependency.id(), manager.checkDependencies("my-extension"));
});
```

**检查配置：**
```java
MyExtensionConfig config = getConfig();
getLogger().info("Config: {}", config);
```

## 📖 更新日志

### v1.0.0
- 初始版本
- 基础扩展系统
- 标签页API
- 动画支持
- 图片支持

### v1.1.0
- 扩展依赖管理
- 扩展兼容性检查
- 扩展热重载
- 扩展配置助手

### v1.2.0
- 扩展事件总线
- 扩展命令系统
- 扩展注册表
- 扩展加载器

## 🤝 贡献指南

### 贡献代码

1. Fork仓库
2. 创建特性分支
3. 提交代码更改
4. 推送到Fork
5. 创建Pull Request

### 贡献文档

1. Fork仓库
2. 修改文档
3. 提交更改
4. 创建Pull Request

### 报告问题

1. 检查是否有重复的问题
2. 提供详细的描述
3. 提供复现步骤
4. 提供日志和错误信息
5. 提供环境信息

## 📜 许可证

StarX 扩展系统采用 AGPL-3.0 许可证。

详情请参阅 LICENSE 文件。

## 🙏 鸣谢

感谢所有为StarX项目做出贡献的人们！

- [Elytrium](https://github.com/Elytrium) - StarX的原始作者
- 所有贡献者
- 所有使用StarX的用户

---

**文档最后更新:** 2025年1月
**StarX版本:** 0.6.3
