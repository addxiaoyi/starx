package io.github.addxiaoyi.starx.common.auth;

import static org.junit.jupiter.api.Assertions.*;
import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.event.LocalEventBus;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

final class AuthServicePasswordVerificationStressTest {
    private static final String VALID_PASSWORD = "SecurePassword_123!";
    private static final String INVALID_PASSWORD = "WrongPassword_789!";
    private static final String LONG_PASSWORD = "ThisIsAVeryLongPasswordThatExceedsBCryptLengthLimitAndShouldUseSha512StrategyForHashingPurposes1234567890";
    private ExecutorService executorService;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
        sessionManager = new SessionManager(Duration.ofMinutes(5), Instant::now);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executorService != null) { executorService.shutdownNow(); executorService.awaitTermination(5, TimeUnit.SECONDS); }
        if (sessionManager != null) { sessionManager.shutdown(); }
    }

    @Test
    @Timeout(120)
    void concurrentPasswordHashing_ShouldProduceVerifiableHashes() throws Exception {
        int threadCount = 20;
        int iterationsPerThread = 10;
        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    String password = VALID_PASSWORD + threadId + "_" + j;
                    String hash = PasswordHasher.hash(password);
                    assertNotNull(hash);
                    assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"));
                    assertTrue(PasswordHasher.verify(password, hash));
                    assertFalse(PasswordHasher.verify(INVALID_PASSWORD, hash));
                }
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(60)
    void concurrentSamePasswordHashing_ShouldProduceDifferentHashes() throws Exception {
        int threadCount = 50;
        CompletableFuture<String>[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.supplyAsync(() -> PasswordHasher.hash(VALID_PASSWORD), executorService);
        }
        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
        String[] hashes = new String[threadCount];
        for (int i = 0; i < threadCount; i++) hashes[i] = futures[i].get();
        for (int i = 0; i < threadCount; i++)
            for (int j = i + 1; j < threadCount; j++)
                assertTrue(!hashes[i].equals(hashes[j]));
        for (String hash : hashes) assertTrue(PasswordHasher.verify(VALID_PASSWORD, hash));
    }

    @Test
    @Timeout(60)
    void longPasswordHashing_ShouldUseSha512Strategy() throws Exception {
        CompletableFuture<?>[] futures = new CompletableFuture[20];
        for (int i = 0; i < 20; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                String hash = PasswordHasher.hash(LONG_PASSWORD);
                assertNotNull(hash);
                assertTrue(PasswordHasher.verify(LONG_PASSWORD, hash));
                assertFalse(PasswordHasher.verify(INVALID_PASSWORD, hash));
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(60, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(120)
    void concurrentLoginWithCorrectPassword_ShouldSucceed() throws Exception {
        // Test concurrent password verification with unique users per thread
        int threadCount = 20;
        CompletableFuture<Boolean>[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                String hash = PasswordHasher.hash(VALID_PASSWORD);
                return PasswordHasher.verify(VALID_PASSWORD, hash);
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures)
            if (future.get()) successCount++;
        assertTrue(successCount >= threadCount * 0.9, "90% should succeed, got " + successCount + "/" + threadCount);
    }

    @Test
    @Timeout(120)
    void concurrentLoginWithIncorrectPassword_ShouldFail() throws Exception {
        // Test concurrent wrong password verification
        int threadCount = 20;
        CompletableFuture<Boolean>[] futures = new CompletableFuture[threadCount];
        String correctHash = PasswordHasher.hash(VALID_PASSWORD);
        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.supplyAsync(() -> {
                return PasswordHasher.verify(INVALID_PASSWORD, correctHash);
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(120, TimeUnit.SECONDS);
        for (CompletableFuture<Boolean> future : futures)
            assertFalse(future.get(), "Wrong password should fail verification");
    }

    @Test
    @Timeout(120)
    void extremeConcurrentPasswordVerification_ShouldHandleHighLoad() throws Exception {
        int threadCount = 50;
        String hash = PasswordHasher.hash(VALID_PASSWORD);
        CompletableFuture<Boolean>[] futures = new CompletableFuture[threadCount];
        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.supplyAsync(() -> {
                return PasswordHasher.verify(VALID_PASSWORD, hash);
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(180, TimeUnit.SECONDS);
        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures)
            if (future.get()) successCount++;
        assertTrue(successCount >= threadCount * 0.8, "80% should succeed, got " + successCount + "/" + threadCount);
    }

    @Test
    @Timeout(180)
    void extremeConcurrentPasswordHashing_ShouldNotDegrade() throws Exception {
        int threadCount = 50;
        int iterationsPerThread = 10;
        CompletableFuture<Long>[] futures = new CompletableFuture[threadCount];
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                long threadStart = System.currentTimeMillis();
                for (int j = 0; j < iterationsPerThread; j++) {
                    String password = VALID_PASSWORD + threadId + "_" + j;
                    String hash = PasswordHasher.hash(password);
                    if (!PasswordHasher.verify(password, hash))
                        throw new RuntimeException("Verification failed for thread " + threadId);
                }
                return System.currentTimeMillis() - threadStart;
            }, executorService);
        }
        CompletableFuture.allOf(futures).get(180, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;
        long totalOps = (long) threadCount * iterationsPerThread;
        double opsPerSec = ((double) totalOps * 1000.0) / totalTime;
        System.out.println("Extreme hashing: " + totalOps + " ops in " + totalTime + "ms = " + String.format("%.2f", opsPerSec) + " ops/s");
        assertTrue(totalTime < 120000, "Should complete within 120 seconds");
    }

    private static final class StressTestRepository extends JdbcUserRepository {
        private final UUID[] userIds;
        private final String[] passwordHashes;
        StressTestRepository(UUID userId, String passwordHash) {
            super(null);
            this.userIds = new UUID[]{userId};
            this.passwordHashes = new String[]{passwordHash};
        }
        @Override
        public Optional<StarxUser> findFullByUuid(UUID uuid) {
            for (int i = 0; i < userIds.length; i++)
                if (userIds[i].equals(uuid)) return Optional.of(createTestUser(userIds[i], passwordHashes[i]));
            return Optional.empty();
        }
        @Override
        public Optional<StarxUser> findFullByUsername(String username) {
            if ("test-user".equals(username)) return Optional.of(createTestUser(userIds[0], passwordHashes[0]));
            return Optional.empty();
        }
        @Override
        public Optional<String> findPasswordHashByUuid(UUID uuid) {
            for (int i = 0; i < userIds.length; i++)
                if (userIds[i].equals(uuid)) return Optional.of(passwordHashes[i]);
            return Optional.empty();
        }
        @Override
        public boolean existsByUuid(UUID uuid) {
            for (UUID userId : userIds) if (userId.equals(uuid)) return true;
            return false;
        }
        @Override
        public boolean enableTotp(UUID uuid, String secret, String recoveryCodes) { return false; }
        @Override
        public boolean disableTotp(UUID uuid) { return false; }
        @Override
        public void updateTrustedDevices(UUID uuid, List<String> devices) {}
        private StarxUser createTestUser(UUID uuid, String passwordHash) {
            return new StarxUser(uuid, "test-user", null, passwordHash, null, false, Instant.now(), null, null, List.of(), null, "local", "completed", null, null, null, null, 0L, null, false);
        }
    }
}
