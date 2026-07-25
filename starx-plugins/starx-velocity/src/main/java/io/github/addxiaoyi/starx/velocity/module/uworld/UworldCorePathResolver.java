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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.github.addxiaoyi.starx.velocity.module.uworld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

public final class UworldCorePathResolver {

  private static final String LEGACY_WARNING =
      "Legacy limbo/core.yml is in use; migrate it to uworld/core.yml";

  private UworldCorePathResolver() {
  }

  public static Path resolve(Path dataDirectory, Consumer<String> warningSink) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    Objects.requireNonNull(warningSink, "warningSink");

    Path uworldDirectory = dataDirectory.resolve("uworld");
    Path legacyDirectory = dataDirectory.resolve("limbo");
    boolean hasUworldCore = Files.isRegularFile(uworldDirectory.resolve("core.yml"));
    boolean hasLegacyCore = Files.isRegularFile(legacyDirectory.resolve("core.yml"));

    if (!hasUworldCore && hasLegacyCore) {
      warningSink.accept(LEGACY_WARNING);
      return legacyDirectory;
    }
    return uworldDirectory;
  }
}
