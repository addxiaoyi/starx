package io.github.addxiaoyi.starx.velocity.repository;

import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.repository.UserRepository;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Deprecated(forRemoval = true)
public final class InMemoryUserRepository implements UserRepository {
    private final Map<UUID, UserDto> usersByUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> uuidByUsername = new ConcurrentHashMap<>();
    private final Map<String, UUID> uuidByEmail = new ConcurrentHashMap<>();

    @Override
    public Optional<UserDto> findByUuid(UUID uuid) {
        return Optional.ofNullable(this.usersByUuid.get(uuid));
    }

    @Override
    public Optional<UserDto> findByUsername(String username) {
        return Optional.ofNullable(this.uuidByUsername.get(normalizeUsername(username)))
            .map(this.usersByUuid::get);
    }

    @Override
    public Optional<UserDto> findByEmail(String email) {
        return Optional.ofNullable(this.uuidByEmail.get(email)).map(this.usersByUuid::get);
    }

    @Override
    public boolean existsByUsername(String username) {
        return this.uuidByUsername.containsKey(normalizeUsername(username));
    }

    @Override
    public boolean existsByUuid(UUID uuid) {
        return this.usersByUuid.containsKey(uuid);
    }

    @Override
    public void save(UserDto user) {
        UserDto previous = this.usersByUuid.put(user.uuid(), user);
        if (previous != null) {
            this.uuidByUsername.remove(normalizeUsername(previous.username()), user.uuid());
        }
        this.uuidByUsername.put(normalizeUsername(user.username()), user.uuid());
        if (user.email() != null && !user.email().isBlank()) {
            this.uuidByEmail.put(user.email(), user.uuid());
        }
    }

    @Override
    public void delete(UUID uuid) {
        UserDto removed = this.usersByUuid.remove(uuid);
        if (removed != null) {
            this.uuidByUsername.remove(normalizeUsername(removed.username()), uuid);
            if (removed.email() != null) {
                this.uuidByEmail.remove(removed.email());
            }
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
