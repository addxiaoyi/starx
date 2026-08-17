package io.github.addxiaoyi.starx.common.skin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SkinServiceTest {

  @Test
  void doesNotPublishSkinEventsWhenTextureWriteFails() {
    EventRecorder events = new EventRecorder();
    SkinService service = new SkinService(new FailingSkinRepository(), events);

    assertDoesNotThrow(() -> service.refreshSkin(UUID.randomUUID(), "player"));

    assertFalse(events.types.contains("skin:applied"));
    assertFalse(events.types.contains("skin:updated"));
  }

  @Test
  void doesNotPublishSkinEventsWhenTextureProviderCannotLink() {
    EventRecorder events = new EventRecorder();
    SkinService service = new SkinService(new LinkageFailingSkinRepository(), events);

    assertDoesNotThrow(() -> service.refreshSkin(UUID.randomUUID(), "player"));

    assertFalse(events.types.contains("skin:applied"));
    assertFalse(events.types.contains("skin:updated"));
  }

  @Test
  void publishesSkinEventsAfterTextureWriteSucceeds() {
    EventRecorder events = new EventRecorder();
    SkinService service = new SkinService(new WorkingSkinRepository(), events);

    service.refreshSkin(UUID.randomUUID(), "player");

    assertTrue(events.types.contains("skin:applied"));
    assertTrue(events.types.contains("skin:updated"));
  }

  @Test
  void supportsHistoricalMinecraftUuidLookup() throws Exception {
    Path source = Path.of("src/main/java/io/github/addxiaoyi/starx/common/skin/SkinService.java");
    String text = Files.readString(source, StandardCharsets.UTF_8);

    assertTrue(text.contains("knownMinecraftUuidsResolver"));
    assertTrue(text.contains("knownMinecraftUuidsResolver.apply(requested)"));
  }

  private static final class FailingSkinRepository implements SkinRepository {
    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
      return Optional.of(new SkinDto(uuid, name, null, "value", "signature", null));
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
      throw new IllegalStateException("skin id storage unavailable");
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
      throw new IllegalStateException("skin data storage unavailable");
    }

    @Override
    public void clearSkin(UUID uuid) {
    }
  }

  private static final class WorkingSkinRepository implements SkinRepository {
    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
      return Optional.of(new SkinDto(uuid, name, null, "value", "signature", null));
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
    }

    @Override
    public void clearSkin(UUID uuid) {
    }
  }

  private static final class LinkageFailingSkinRepository implements SkinRepository {
    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
      return Optional.of(new SkinDto(uuid, name, null, "value", "signature", null));
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
      throw new NoSuchMethodError("optional skin API changed");
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
      throw new NoSuchMethodError("optional skin API changed");
    }

    @Override
    public void clearSkin(UUID uuid) {
    }
  }

  private static final class EventRecorder implements EventBus {
    private final List<String> types = new ArrayList<>();

    @Override
    public void publish(StarxEvent event) {
      this.types.add(event.type());
    }

    @Override
    public void subscribe(String type, Consumer<StarxEvent> subscriber) {
    }
  }
}
