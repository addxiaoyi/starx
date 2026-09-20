package io.github.addxiaoyi.starx.velocity.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ModuleManagerTest {

  @Test
  void startsShutdownInRegistrationOrderBeforeDisablingModulesInReverse() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new RecordingModule("starx.uworld", events, false, false));
    manager.register(new RecordingModule("starx.auth", events, false, false));
    manager.register(new RecordingModule("starx.diagnostics", events, false, false));

    manager.enableAll();
    manager.disableAll();

    assertEquals(List.of(
        "enable:starx.uworld",
        "enable:starx.auth",
        "enable:starx.diagnostics",
        "shutdown-start:starx.uworld",
        "shutdown-start:starx.auth",
        "shutdown-start:starx.diagnostics",
        "disable:starx.diagnostics",
        "disable:starx.auth",
        "disable:starx.uworld"), events);
  }

  @Test
  void enableFailureRollsBackPreviouslyEnabledModules() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new RecordingModule("starx.uworld", events, false, false));
    manager.register(new RecordingModule("starx.auth", events, true, false));
    manager.register(new RecordingModule("starx.diagnostics", events, false, false));

    IllegalStateException error = assertThrows(IllegalStateException.class, manager::enableAll);

    assertTrue(error.getMessage().contains("starx.auth"));
    assertEquals(List.of(
        "enable:starx.uworld",
        "enable:starx.auth",
        "disable:starx.auth",
        "disable:starx.uworld"), events);
    manager.disableAll();
  }

  @Test
  void enableRollbackRetainsTheModuleThatFailedToStop() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new FailOnceDisableModule("starx.uworld", events));
    manager.register(new RecordingModule("starx.auth", events, true, false));

    assertThrows(IllegalStateException.class, manager::enableAll);
    manager.disableAll();

    assertEquals(List.of(
        "enable:starx.uworld",
        "enable:starx.auth",
        "disable:starx.auth",
        "disable:starx.uworld",
        "shutdown-start:starx.uworld",
        "disable:starx.uworld"), events);
  }

  @Test
  void failedEnableCleanupIsRetriedByDisableAll() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new FailEnableAndOnceDisableModule("starx.uworld", events));

    IllegalStateException error = assertThrows(IllegalStateException.class, manager::enableAll);

    assertEquals(1, error.getSuppressed().length);
    manager.disableAll();
    assertEquals(List.of(
        "enable:starx.uworld",
        "disable:starx.uworld",
        "shutdown-start:starx.uworld",
        "disable:starx.uworld"), events);
  }

  @Test
  void shutdownContinuesAndNamesEveryModuleFailure() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new RecordingModule("starx.uworld", events, false, true));
    manager.register(new RecordingModule("starx.auth", events, false, true));
    manager.register(new RecordingModule("starx.diagnostics", events, false, false));
    manager.enableAll();

    IllegalStateException error = assertThrows(IllegalStateException.class, manager::disableAll);

    assertEquals("One or more modules failed to stop", error.getMessage());
    assertEquals(2, error.getSuppressed().length);
    assertTrue(error.getSuppressed()[0].getMessage().contains("starx.auth"));
    assertTrue(error.getSuppressed()[1].getMessage().contains("starx.uworld"));
    assertEquals(List.of(
        "enable:starx.uworld",
        "enable:starx.auth",
        "enable:starx.diagnostics",
        "shutdown-start:starx.uworld",
        "shutdown-start:starx.auth",
        "shutdown-start:starx.diagnostics",
        "disable:starx.diagnostics",
        "disable:starx.auth",
        "disable:starx.uworld"), events);
  }

  @Test
  void disableRetriesOnlyTheModuleThatFailedToStop() {
    List<String> events = new ArrayList<>();
    ModuleManager manager = new ModuleManager(name -> true);
    manager.register(new RecordingModule("starx.uworld", events, false, false));
    manager.register(new FailOnceDisableModule("starx.auth", events));
    manager.register(new RecordingModule("starx.diagnostics", events, false, false));
    manager.enableAll();

    assertThrows(IllegalStateException.class, manager::disableAll);
    manager.disableAll();

    assertEquals(List.of(
        "enable:starx.uworld",
        "enable:starx.auth",
        "enable:starx.diagnostics",
        "shutdown-start:starx.uworld",
        "shutdown-start:starx.auth",
        "shutdown-start:starx.diagnostics",
        "disable:starx.diagnostics",
        "disable:starx.auth",
        "disable:starx.uworld",
        "shutdown-start:starx.auth",
        "disable:starx.auth"), events);
  }

  @Test
  void legacyLimboLookupReturnsTheSingleUworldModule() {
    ModuleManager manager = new ModuleManager(name -> true);
    RecordingModule uworld = new RecordingModule(
        "starx.uworld", new ArrayList<>(), false, false);
    manager.register(uworld);

    assertSame(uworld, manager.get("starx.limbo").orElseThrow());
    assertEquals(1, manager.all().size());
  }

  private record RecordingModule(
      String name,
      List<String> events,
      boolean failEnable,
      boolean failDisable
  ) implements VelocityModule {
    @Override
    public void onEnable() {
      this.events.add("enable:" + this.name);
      if (this.failEnable) {
        throw new IllegalStateException("enable failed");
      }
    }

    @Override
    public void onShutdownStart() {
      this.events.add("shutdown-start:" + this.name);
    }

    @Override
    public void onDisable() {
      this.events.add("disable:" + this.name);
      if (this.failDisable) {
        throw new IllegalStateException("disable failed");
      }
    }
  }

  private static final class FailOnceDisableModule implements VelocityModule {
    private final String name;
    private final List<String> events;
    private int disableAttempts;

    private FailOnceDisableModule(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    @Override
    public String name() {
      return this.name;
    }

    @Override
    public void onEnable() {
      this.events.add("enable:" + this.name);
    }

    @Override
    public void onShutdownStart() {
      this.events.add("shutdown-start:" + this.name);
    }

    @Override
    public void onDisable() {
      this.events.add("disable:" + this.name);
      if (this.disableAttempts++ == 0) {
        throw new IllegalStateException("disable failed once");
      }
    }
  }

  private static final class FailEnableAndOnceDisableModule implements VelocityModule {
    private final String name;
    private final List<String> events;
    private int disableAttempts;

    private FailEnableAndOnceDisableModule(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    @Override
    public String name() {
      return this.name;
    }

    @Override
    public void onEnable() {
      this.events.add("enable:" + this.name);
      throw new IllegalStateException("enable failed");
    }

    @Override
    public void onShutdownStart() {
      this.events.add("shutdown-start:" + this.name);
    }

    @Override
    public void onDisable() {
      this.events.add("disable:" + this.name);
      if (this.disableAttempts++ == 0) {
        throw new IllegalStateException("disable failed once");
      }
    }
  }
}
