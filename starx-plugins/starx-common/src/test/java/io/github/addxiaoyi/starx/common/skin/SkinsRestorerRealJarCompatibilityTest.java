package io.github.addxiaoyi.starx.common.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SkinsRestorerRealJarCompatibilityTest {
  private static final String JAR_PROPERTY = "starx.skinsrestorer.jar";

  @Test
  void matchesTheCurrentSkinsRestorerStorageContract() throws Exception {
    Path jar = configuredJar();
    try (URLClassLoader loader = new SkinsRestorerClassLoader(jar.toUri().toURL())) {
      Class<?> identifierType = Class.forName(
          "net.skinsrestorer.api.property.SkinIdentifier", true, loader);
      Class<?> propertyType = Class.forName(
          "net.skinsrestorer.api.property.SkinProperty", true, loader);
      Class<?> storageType = Class.forName(
          "net.skinsrestorer.shared.storage.SkinStorageImpl", true, loader);
      Class<?> playerStorageType = Class.forName(
          "net.skinsrestorer.shared.storage.PlayerStorageImpl", true, loader);

      Method identifierFactory = identifierType.getMethod("ofCustom", String.class);
      Method propertyFactory = propertyType.getMethod("of", String.class, String.class);
      assertEquals(identifierType, identifierFactory.getReturnType());
      assertEquals(propertyType, propertyFactory.getReturnType());
      assertEquals(propertyType, propertyFactory.invoke(null, "value", "signature").getClass());

      assertNotNull(storageType.getMethod("setCustomSkinData", String.class, propertyType));
      assertNotNull(storageType.getMethod("removeSkinData", identifierType));
      assertNotNull(playerStorageType.getMethod(
          "getSkinForPlayer", UUID.class, String.class, boolean.class));
      assertNotNull(playerStorageType.getMethod(
          "setSkinIdOfPlayer", UUID.class, identifierType));

      Class<?> storageApi = Class.forName(
          "net.skinsrestorer.api.storage.SkinStorage", true, loader);
      Object storage = Proxy.newProxyInstance(
          loader,
          new Class<?>[] {storageApi},
          (proxy, method, arguments) -> null);
      Method writerFinder = SkinsRestorerSkinRepository.class.getDeclaredMethod(
          "findSkinDataWriter", Object.class, String.class, String.class, String.class);
      writerFinder.trySetAccessible();
      Object writer = writerFinder.invoke(
          null, storage, "starx-real", "value", "signature");
      assertNotNull(writer);

      Method selectedMethod = writer.getClass().getDeclaredMethod("method");
      selectedMethod.trySetAccessible();
      Method selected = (Method) selectedMethod.invoke(writer);
      assertEquals("setCustomSkinData", selected.getName());
      assertEquals(propertyType, selected.getParameterTypes()[1]);
    }
  }

  private static Path configuredJar() {
    String configured = System.getProperty(JAR_PROPERTY, "").trim();
    Assumptions.assumeTrue(!configured.isEmpty(),
        "Set -PskinsRestorerJar=... to run the real SkinsRestorer compatibility check");
    Path jar = Path.of(configured).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(jar), "SkinsRestorer JAR does not exist: " + jar);
    return jar;
  }

  private static final class SkinsRestorerClassLoader extends URLClassLoader {
    private SkinsRestorerClassLoader(URL jar) {
      super(new URL[] {jar}, SkinsRestorerRealJarCompatibilityTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith("net.skinsrestorer.")) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException ignored) {
            loaded = super.loadClass(name, resolve);
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }
  }
}
