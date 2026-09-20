package io.github.addxiaoyi.starx.server;

import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

final class StarxServerCommandTest {

  @Test
  void reportsSkinMetadataWithoutPrintingTheTexture() {
    UUID uuid = UUID.fromString("4f06bce0-32d7-4d4d-bb17-9f7e92ae8701");
    BackendBridgeSession session = new BackendBridgeSession(
        "lobby",
        ServerPlatform.PAPER,
        () -> Map.of(),
        (requestedUuid, name) -> Optional.of(new BackendSkinProfile(
            requestedUuid,
            name,
            "skinsrestorer",
            "secret-texture-value",
            "secret-signature")),
        Clock.systemUTC());
    List<String> messages = new ArrayList<>();
    CommandSender sender = sender(messages);

    CompatibilityReport compatibility = new CompatibilityReport(
        "test", "", System.getProperty("java.version", ""), Instant.EPOCH, List.of());
    new StarxServerCommand(session, compatibility).onCommand(
        sender,
        null,
        "starxserver",
        new String[]{"skin", uuid.toString(), "Alex"});

    String output = String.join("\n", messages);
    assertTrue(output.contains("found=true"));
    assertTrue(output.contains("provider=skinsrestorer"));
    assertTrue(output.contains("value-chars=20"));
    assertTrue(output.contains("signature=true"));
    assertTrue(!output.contains("secret-texture-value"));
    assertTrue(!output.contains("secret-signature"));
  }

  private static CommandSender sender(List<String> messages) {
    return (CommandSender) Proxy.newProxyInstance(
        CommandSender.class.getClassLoader(),
        new Class<?>[]{CommandSender.class},
        (proxy, method, args) -> {
          if (method.getName().equals("sendMessage") && args != null) {
            for (Object value : args) {
              if (value instanceof Component component) {
                messages.add(component.toString());
              }
            }
          }
          if (method.getReturnType() == boolean.class) {
            return true;
          }
          return null;
        });
  }
}
