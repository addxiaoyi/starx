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

import java.util.Objects;

public final class UworldCreationException extends RuntimeException {

  public UworldCreationException(String owner, String world, String message) {
    super("owner=" + Objects.requireNonNull(owner, "owner")
        + ", world=" + Objects.requireNonNull(world, "world")
        + ": " + Objects.requireNonNull(message, "message"));
  }
}
