package io.github.addxiaoyi.starx.limbo.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

final class LitematicFileTest {

  @Test
  void decodesPackedPaletteAcrossLongBoundaries() {
    List<CompoundBinaryTag> palette = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      palette.add(block("minecraft:test_" + index));
    }
    int[] states = new int[20];
    for (int index = 0; index < states.length; index++) {
      states[index] = index;
    }

    LitematicFile file = new LitematicFile(root(region(
        20, 1, 1,
        0, 0, 0,
        palette,
        states,
        List.of(),
        List.of())));

    assertEquals(1, file.regionCount());
    assertEquals(20L, file.totalVolume());
    for (int index = 0; index < states.length; index++) {
      assertEquals(index, file.region(0).paletteIndex(index, 0, 0));
    }
  }

  @Test
  void mapsNegativeRegionSizesIntoSignedWorldCoordinates() {
    LitematicFile file = new LitematicFile(root(region(
        -2, 1, -2,
        10, 5, 20,
        List.of(block("minecraft:air"), block("minecraft:stone")),
        new int[] {0, 1, 1, 0},
        List.of(),
        List.of())));

    LitematicFile.Region region = file.region(0);
    assertEquals(9, region.worldX(0));
    assertEquals(10, region.worldX(1));
    assertEquals(19, region.worldZ(0));
    assertEquals(20, region.worldZ(1));
    assertEquals(5, region.worldY(0));
  }

  @Test
  void parsesMultipleRegionsAndBlockEntities() {
    CompoundBinaryTag tileEntity = CompoundBinaryTag.builder()
        .putInt("x", 1)
        .putInt("y", 0)
        .putInt("z", 0)
        .putString("CustomName", "test")
        .build();
    CompoundBinaryTag regions = CompoundBinaryTag.builder()
        .put("first", region(
            2, 1, 1,
            0, 0, 0,
            List.of(block("minecraft:air"), block("minecraft:furnace")),
            new int[] {0, 1},
            List.of(tileEntity),
            List.of()))
        .put("second", region(
            1, 1, 1,
            20, 0, 30,
            List.of(block("minecraft:stone")),
            new int[] {0},
            List.of(),
            List.of()))
        .build();

    LitematicFile file = new LitematicFile(CompoundBinaryTag.builder()
        .put("Regions", regions)
        .build());

    assertEquals(2, file.regionCount());
    assertEquals(3L, file.totalVolume());
    assertEquals(1, file.blockEntityCount());
    assertEquals(1, file.region(0).blockEntities().size());
  }

  @Test
  void rejectsOrdinaryEntitiesRatherThanSilentlyDroppingThem() {
    CompoundBinaryTag entity = CompoundBinaryTag.builder()
        .putString("id", "minecraft:armor_stand")
        .build();

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new LitematicFile(root(region(
            1, 1, 1,
            0, 0, 0,
            List.of(block("minecraft:air")),
            new int[] {0},
            List.of(),
            List.of(entity)))));

    assertTrue(error.getMessage().contains("ordinary entities"));
  }

  @Test
  void rejectsMalformedPackedStorageLength() {
    CompoundBinaryTag malformed = CompoundBinaryTag.builder()
        .put("Position", vector(0, 0, 0))
        .put("Size", vector(20, 1, 1))
        .put("BlockStatePalette", compoundList(List.of(
            block("minecraft:air"),
            block("minecraft:stone"))))
        .putLongArray("BlockStates", new long[0])
        .put("TileEntities", compoundList(List.of()))
        .put("Entities", compoundList(List.of()))
        .build();

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new LitematicFile(root(malformed)));

    assertTrue(error.getMessage().contains("length mismatch"));
  }

  private static CompoundBinaryTag root(CompoundBinaryTag region) {
    return CompoundBinaryTag.builder()
        .put("Regions", CompoundBinaryTag.builder()
            .put("test", region)
            .build())
        .build();
  }

  private static CompoundBinaryTag region(
      int sizeX,
      int sizeY,
      int sizeZ,
      int positionX,
      int positionY,
      int positionZ,
      List<CompoundBinaryTag> palette,
      int[] states,
      List<CompoundBinaryTag> tileEntities,
      List<CompoundBinaryTag> entities
  ) {
    int bits = Math.max(
        2,
        Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
    return CompoundBinaryTag.builder()
        .put("Position", vector(positionX, positionY, positionZ))
        .put("Size", vector(sizeX, sizeY, sizeZ))
        .put("BlockStatePalette", compoundList(palette))
        .putLongArray("BlockStates", pack(bits, states))
        .put("TileEntities", compoundList(tileEntities))
        .put("Entities", compoundList(entities))
        .build();
  }

  private static CompoundBinaryTag vector(int x, int y, int z) {
    return CompoundBinaryTag.builder()
        .putInt("x", x)
        .putInt("y", y)
        .putInt("z", z)
        .build();
  }

  private static CompoundBinaryTag block(String name) {
    return CompoundBinaryTag.builder()
        .putString("Name", name)
        .build();
  }

  private static ListBinaryTag compoundList(List<CompoundBinaryTag> values) {
    ListBinaryTag.Builder<CompoundBinaryTag> builder =
        ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
    values.forEach(builder::add);
    return builder.build();
  }

  private static long[] pack(int bits, int[] values) {
    long[] packed = new long[(int) ((((long) values.length * bits) + 63L) / 64L)];
    long mask = (1L << bits) - 1L;
    for (int index = 0; index < values.length; index++) {
      long offset = (long) index * bits;
      int startLong = (int) (offset >>> 6);
      int startBit = (int) (offset & 63L);
      packed[startLong] |= ((long) values[index] & mask) << startBit;
      if (startBit + bits > 64) {
        packed[startLong + 1] |= ((long) values[index] & mask) >>> (64 - startBit);
      }
    }
    return packed;
  }
}
