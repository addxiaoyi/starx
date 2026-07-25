package io.github.addxiaoyi.starx.server;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;

final class QrCodeImage {
  private static final int SIZE = 128;
  private static final int DARK = 0xFF111827;
  private static final int LIGHT = 0xFFF8FAFC;

  private QrCodeImage() {
  }

  static BufferedImage render(String value) {
    try {
      BitMatrix bits = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, SIZE, SIZE);
      BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
          image.setRGB(x, y, bits.get(x, y) ? DARK : LIGHT);
        }
      }
      return image;
    } catch (WriterException error) {
      throw new IllegalArgumentException("无法生成 2FA 二维码", error);
    }
  }
}
