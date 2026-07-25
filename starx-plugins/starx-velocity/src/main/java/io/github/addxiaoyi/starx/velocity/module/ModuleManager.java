/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module;

import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class ModuleManager {
    private final Map<String, VelocityModule> modules = new LinkedHashMap<String, VelocityModule>();
    private final List<VelocityModule> enabledModules = new ArrayList<>();
    private final Predicate<String> enabled;

    public ModuleManager(StarxConfig config) {
        this(Objects.requireNonNull(config, "config")::isModuleEnabled);
    }

    ModuleManager(Predicate<String> enabled) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    public void register(VelocityModule module) {
        Objects.requireNonNull(module, "module");
        VelocityModule existing = this.modules.putIfAbsent(module.name(), module);
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate Velocity module name: " + module.name());
        }
    }

    public void enableAll() {
        for (VelocityModule module : this.modules.values()) {
            if (!this.enabled.test(module.name())) continue;
            this.enabledModules.add(module);
            try {
                module.onEnable();
            } catch (RuntimeException error) {
                IllegalStateException failure = new IllegalStateException(
                    "Unable to enable module " + module.name(), error);
                this.rollbackEnabled(failure);
                throw failure;
            }
        }
    }

    public void disableAll() {
        RuntimeException failure = null;
        for (VelocityModule module : this.enabledModules) {
            try {
                module.onShutdownStart();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = new IllegalStateException("One or more modules failed to stop");
                }
                failure.addSuppressed(new IllegalStateException(
                    "Unable to start shutdown for module " + module.name(), error));
            }
        }
        List<VelocityModule> reverse = new ArrayList<>(this.enabledModules);
        Collections.reverse(reverse);
        List<VelocityModule> failedModules = new ArrayList<>();
        for (VelocityModule module : reverse) {
            try {
                module.onDisable();
            } catch (RuntimeException error) {
                failedModules.add(module);
                if (failure == null) {
                    failure = new IllegalStateException("One or more modules failed to stop");
                }
                failure.addSuppressed(new IllegalStateException(
                    "Unable to stop module " + module.name(), error));
            }
        }
        Collections.reverse(failedModules);
        this.enabledModules.clear();
        this.enabledModules.addAll(failedModules);
        if (failure != null) {
            throw failure;
        }
    }

    public Optional<VelocityModule> get(String name) {
        String normalized = "starx.limbo".equals(name) ? "starx.uworld" : name;
        return Optional.ofNullable(this.modules.get(normalized));
    }

    public Collection<VelocityModule> all() {
        return List.copyOf(this.modules.values());
    }

    private void rollbackEnabled(IllegalStateException failure) {
        List<VelocityModule> reverse = new ArrayList<>(this.enabledModules);
        Collections.reverse(reverse);
        List<VelocityModule> failedModules = new ArrayList<>();
        for (VelocityModule module : reverse) {
            try {
                module.onDisable();
            } catch (RuntimeException error) {
                failedModules.add(module);
                failure.addSuppressed(new IllegalStateException(
                    "Unable to roll back module " + module.name(), error));
            }
        }
        Collections.reverse(failedModules);
        this.enabledModules.clear();
        this.enabledModules.addAll(failedModules);
    }
}
