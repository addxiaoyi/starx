package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NapCatFrameBufferTest {

  @Test
  void joinsFragmentsUntilFinalFrame() {
    NapCatFrameBuffer buffer = new NapCatFrameBuffer(16);

    assertTrue(buffer.append("hello ", false).isEmpty());
    assertEquals("hello world", buffer.append("world", true).orElseThrow());
  }

  @Test
  void discardsEveryFragmentOfAnOversizedMessage() {
    NapCatFrameBuffer buffer = new NapCatFrameBuffer(8);

    assertTrue(buffer.append("123456", false).isEmpty());
    assertTrue(buffer.append("789", false).isEmpty());
    assertTrue(buffer.append("tail", true).isEmpty());
    assertEquals("ok", buffer.append("ok", true).orElseThrow());
  }
}
