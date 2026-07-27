package io.github.addxiaoyi.starx.velocity.website;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import io.github.addxiaoyi.starx.website.PlayerTextureRecord;
import io.github.addxiaoyi.starx.website.TextureKind;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SkinsRestorerTextureSourceTest {
  private static final UUID PLAYER_ID =
      UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
  private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

  @Test
  void mergesHistoricalPlayersAndLetsOnlineNamesWin() {
    UUID historicalOnly = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    List<SkinsRestorerTextureSource.PlayerRef> merged =
        SkinsRestorerTextureSource.mergePlayers(
            List.of(
                new SkinsRestorerTextureSource.PlayerRef(PLAYER_ID, "OldName"),
                new SkinsRestorerTextureSource.PlayerRef(historicalOnly, "OfflinePlayer")),
            List.of(new SkinsRestorerTextureSource.PlayerRef(PLAYER_ID, "CurrentName")));

    assertEquals(2, merged.size());
    assertEquals("CurrentName", merged.stream()
        .filter(player -> player.uuid().equals(PLAYER_ID))
        .findFirst().orElseThrow().name());
    assertTrue(merged.stream().anyMatch(player -> player.uuid().equals(historicalOnly)));
  }

  @Test
  void convertsSkinsRestorerPropertyIntoManifestAndValidatedBlobs() throws Exception {
    URI skinUri = URI.create("https://textures.minecraft.net/texture/skin-one");
    URI capeUri = URI.create("https://textures.minecraft.net/texture/cape-one");
    SkinRepository repository = repository(new SkinDto(
        PLAYER_ID,
        "Addxiaoyi",
        "skin-1",
        property(skinUri, capeUri, "slim", 1_722_000_000_000L),
        "signature",
        null));
    byte[] skinPng = png(64, 64);
    byte[] capePng = png(64, 32);
    SkinsRestorerTextureSource source = source(
        repository, uri -> uri.equals(skinUri) ? skinPng : capePng);

    PlayerTextureRecord record = source.snapshot().stream().findFirst().orElseThrow();

    assertEquals(PLAYER_ID.toString(), record.manifest().playerUuid());
    assertEquals("Addxiaoyi", record.manifest().playerName());
    assertEquals("slim", record.manifest().model());
    assertEquals("skinsrestorer", record.manifest().source());
    assertEquals(
        Instant.ofEpochMilli(1_722_000_000_000L).toString(),
        record.manifest().updatedAt());
    assertNotNull(record.manifest().skinHash());
    assertNotNull(record.manifest().capeHash());
    assertTrue(record.blob(TextureKind.SKIN).isPresent());
    assertTrue(record.blob(TextureKind.CAPE).isPresent());
  }

  @Test
  void keepsValidSkinWhenOptionalCapeCannotBeFetched() throws Exception {
    URI skinUri = URI.create("https://textures.minecraft.net/texture/skin-two");
    URI capeUri = URI.create("https://textures.minecraft.net/texture/cape-two");
    SkinRepository repository = repository(new SkinDto(
        PLAYER_ID,
        "Addxiaoyi",
        "skin-2",
        property(skinUri, capeUri, "classic", null),
        "signature",
        null));
    byte[] skinPng = png(64, 64);
    SkinsRestorerTextureSource source = source(repository, uri -> {
      if (uri.equals(capeUri)) {
        throw new IllegalStateException("cape unavailable");
      }
      return skinPng;
    });

    PlayerTextureRecord record = source.snapshot().stream().findFirst().orElseThrow();

    assertNotNull(record.manifest().skinHash());
    assertEquals(null, record.manifest().capeHash());
    assertTrue(record.blob(TextureKind.SKIN).isPresent());
    assertTrue(record.blob(TextureKind.CAPE).isEmpty());
  }

  @Test
  void rejectsMalformedPropertiesWithoutFailingTheSnapshot() {
    SkinRepository repository = repository(new SkinDto(
        PLAYER_ID, "Addxiaoyi", "skin-3", "not-base64", null, null));
    SkinsRestorerTextureSource source = source(repository, uri -> {
      throw new AssertionError("fetcher must not be called");
    });

    assertTrue(source.snapshot().isEmpty());
  }

  @Test
  void blocksLocalAndNonHttpTextureEndpoints() {
    assertFalse(SkinsRestorerTextureSource.isAllowedTextureUri(
        URI.create("http://127.0.0.1/skin.png")));
    assertFalse(SkinsRestorerTextureSource.isAllowedTextureUri(
        URI.create("file:///tmp/skin.png")));
    assertTrue(SkinsRestorerTextureSource.isAllowedTextureUri(
        URI.create("https://8.8.8.8/texture/example")));
  }

  private static SkinsRestorerTextureSource source(
      SkinRepository repository,
      SkinsRestorerTextureSource.TextureFetcher fetcher
  ) {
    return new SkinsRestorerTextureSource(
        () -> List.of(new SkinsRestorerTextureSource.PlayerRef(PLAYER_ID, "Addxiaoyi")),
        repository,
        fetcher,
        Clock.fixed(NOW, ZoneOffset.UTC),
        ignored -> { });
  }

  private static SkinRepository repository(SkinDto skin) {
    return new SkinRepository() {
      @Override
      public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        return Optional.of(skin);
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
    };
  }

  private static String property(URI skin, URI cape, String model, Long timestamp) {
    String timestampField = timestamp == null ? "" : "\"timestamp\":" + timestamp + ",";
    String json = "{" + timestampField + "\"textures\":{"
        + "\"SKIN\":{\"url\":\"" + skin + "\",\"metadata\":{\"model\":\"" + model + "\"}},"
        + "\"CAPE\":{\"url\":\"" + cape + "\"}}}";
    return Base64.getEncoder().encodeToString(
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static byte[] png(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "png", output));
    return output.toByteArray();
  }
}
