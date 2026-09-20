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
import net.kyori.adventure.text.Component;

public sealed interface UworldEnterResult {

  record Accepted(UworldFlowSession session) implements UworldEnterResult {

    public Accepted {
      Objects.requireNonNull(session, "session");
    }
  }

  record Rejected(UworldEnterStatus status, Component reason) implements UworldEnterResult {

    public Rejected {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
