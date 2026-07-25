/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.repository;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    public Optional<UserDto> findByUuid(UUID var1);

    public Optional<UserDto> findByUsername(String var1);

    public Optional<UserDto> findByEmail(String var1);

    public boolean existsByUsername(String var1);

    public boolean existsByUuid(UUID var1);

    public void save(UserDto var1);

    public void delete(UUID var1);
}
