package io.github.addxiaoyi.starx.libs.bytes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class BytesTransformerResizeTest {

  @Test
  void keepsTheRightmostBytesWhenShrinking() {
    byte[] actual = new BytesTransformer.ResizeTransformer(
        2, BytesTransformer.ResizeTransformer.Mode.RESIZE_KEEP_FROM_MAX_LENGTH)
        .transform(new byte[] {1, 2, 3, 4}, false);

    assertArrayEquals(new byte[] {3, 4}, actual);
  }

  @Test
  void rightAlignsBytesWhenGrowing() {
    byte[] actual = new BytesTransformer.ResizeTransformer(
        4, BytesTransformer.ResizeTransformer.Mode.RESIZE_KEEP_FROM_MAX_LENGTH)
        .transform(new byte[] {3, 4}, false);

    assertArrayEquals(new byte[] {0, 0, 3, 4}, actual);
  }

  @Test
  void rejectsNegativeSizes() {
    assertThrows(IllegalArgumentException.class, () ->
        new BytesTransformer.ResizeTransformer(
            -1, BytesTransformer.ResizeTransformer.Mode.RESIZE_KEEP_FROM_MAX_LENGTH)
            .transform(new byte[] {1}, false));
  }
}