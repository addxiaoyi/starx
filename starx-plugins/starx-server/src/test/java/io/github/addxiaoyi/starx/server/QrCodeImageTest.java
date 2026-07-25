package io.github.addxiaoyi.starx.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

final class QrCodeImageTest {
  @Test
  void rendersMapSizedQrWithLightAndDarkPixels() {
    BufferedImage image = QrCodeImage.render(
        "otpauth://totp/StarMC:add?secret=JBSWY3DPEHPK3PXP&issuer=StarMC");
    assertEquals(128, image.getWidth());
    assertEquals(128, image.getHeight());
    long dark = java.util.stream.IntStream.range(0, 128 * 128)
        .filter(index -> image.getRGB(index % 128, index / 128) == 0xFF111827)
        .count();
    assertTrue(dark > 1_000);
  }
}
