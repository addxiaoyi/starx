package io.github.addxiaoyi.starx.protocol.item;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ItemComponentMapContractTest {
  @Test
  void mapOnlyExposesServerSidePacketConstruction() {
    boolean exposesUnsupportedRead = Arrays.stream(ItemComponentMap.class.getMethods())
        .anyMatch(method -> method.getName().equals("read"));

    assertFalse(exposesUnsupportedRead);
  }
}
