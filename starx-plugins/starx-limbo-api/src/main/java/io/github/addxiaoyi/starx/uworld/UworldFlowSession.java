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

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;

public interface UworldFlowSession {

  Player player();

  UworldHandle world();

  UworldPhase phase();

  boolean complete(RegisteredServer target);

  boolean fail(Component reason);

  boolean cancel(Component reason);

  CompletionStage<UworldOutcome> completion();

  void execute(Runnable action);
}
