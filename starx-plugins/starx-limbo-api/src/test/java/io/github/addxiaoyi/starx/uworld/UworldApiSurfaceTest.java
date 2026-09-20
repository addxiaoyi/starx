/*
 * Copyright (C) 2025 StarX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.uworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class UworldApiSurfaceTest {

  private static final List<Class<?>> PUBLIC_TYPES = List.of(
      UworldRuntime.class,
      UworldHandle.class,
      UworldWorldGenerator.class,
      UworldSpec.class,
      UworldWorldEditor.class,
      UworldFlowOptions.class,
      UworldFlowSession.class,
      UworldPhase.class,
      UworldFlowHandler.class,
      UworldEnterStatus.class,
      UworldEnterResult.class,
      UworldOutcomeType.class,
      UworldOutcome.class,
      UworldCreationException.class);

  @Test
  void allManagedContractsArePublic() {
    assertEquals(14, PUBLIC_TYPES.size());
    assertTrue(PUBLIC_TYPES.stream().allMatch(type -> Modifier.isPublic(type.getModifiers())));
  }

  @Test
  void managedContractsDoNotExposeLowLevelRuntimeTypes() {
    Set<String> forbidden = Set.of(
        "io.github.addxiaoyi.starx.LimboFactory",
        "io.github.addxiaoyi.starx.chunk.VirtualWorld",
        "io.github.addxiaoyi.starx.player.LimboPlayer");

    for (Class<?> type : PUBLIC_TYPES) {
      for (Method method : type.getMethods()) {
        String signature = method.toGenericString();
        assertTrue(forbidden.stream().noneMatch(signature::contains), signature);
      }
    }
  }

  @Test
  void editorExposesOnlyManagedWorldOperations() {
    Set<String> methods = Arrays.stream(UworldWorldEditor.class.getDeclaredMethods())
        .map(Method::getName)
        .collect(Collectors.toSet());

    assertEquals(Set.of(
        "createBlock",
        "setBlock",
        "setBiome",
        "fillSkyLight",
        "fillBlockLight",
        "load",
        "isSealed"), methods);
    assertFalse(methods.contains("seal"));
  }

  @Test
  void runtimeExposesObservableWorldAndSessionCounts() {
    Set<String> methods = Arrays.stream(UworldRuntime.class.getDeclaredMethods())
        .map(Method::getName)
        .collect(Collectors.toSet());

    assertTrue(methods.contains("worldCount"));
    assertTrue(methods.contains("sessionCount"));
  }
}
