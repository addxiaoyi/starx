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

import java.time.Duration;
import java.util.Objects;

public record UworldFlowOptions(Duration activeTimeout, Duration transferTimeout) {

  public UworldFlowOptions {
    requirePositive(activeTimeout, "activeTimeout");
    requirePositive(transferTimeout, "transferTimeout");
  }

  public static UworldFlowOptions defaults() {
    return new UworldFlowOptions(Duration.ofMinutes(5), Duration.ofSeconds(15));
  }

  private static void requirePositive(Duration value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
