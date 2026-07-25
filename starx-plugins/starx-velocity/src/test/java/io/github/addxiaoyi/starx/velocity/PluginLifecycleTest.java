package io.github.addxiaoyi.starx.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginLifecycleTest {
    @Test
    void startupFailureRollsBackOwnedResourcesInReverseOrder() {
        PluginLifecycle lifecycle = new PluginLifecycle();
        List<String> closed = new ArrayList<>();
        Exception startupFailure = new Exception("HTTP bind failed");

        Exception thrown = assertThrows(Exception.class, () -> lifecycle.start(() -> {
            lifecycle.own("database", () -> closed.add("database"));
            lifecycle.own("modules", () -> closed.add("modules"));
            lifecycle.own("HTTP API", () -> closed.add("HTTP API"));
            throw startupFailure;
        }));
        lifecycle.close();

        assertSame(startupFailure, thrown);
        assertEquals(List.of("HTTP API", "modules", "database"), closed);
    }

    @Test
    void closeIsIdempotent() throws Exception {
        PluginLifecycle lifecycle = new PluginLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.start(() -> {
            lifecycle.own("database", () -> closed.add("database"));
            lifecycle.own("modules", () -> closed.add("modules"));
        });

        lifecycle.close();
        lifecycle.close();

        assertEquals(List.of("modules", "database"), closed);
    }

    @Test
    void releasedFallbackIsNotClosed() throws Exception {
        PluginLifecycle lifecycle = new PluginLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.start(() -> {
            lifecycle.own("database", () -> closed.add("database"));
            PluginLifecycle.Ownership fallback = lifecycle.own(
                "authentication fallback",
                () -> closed.add("authentication fallback")
            );
            fallback.release();
        });

        lifecycle.close();

        assertEquals(List.of("database"), closed);
    }

    @Test
    void closeContinuesAfterResourceFailure() throws Exception {
        PluginLifecycle lifecycle = new PluginLifecycle();
        List<String> closed = new ArrayList<>();
        IllegalStateException moduleFailure = new IllegalStateException("module stop failed");
        lifecycle.start(() -> {
            lifecycle.own("database", () -> closed.add("database"));
            lifecycle.own("modules", () -> {
                closed.add("modules");
                throw moduleFailure;
            });
            lifecycle.own("HTTP API", () -> closed.add("HTTP API"));
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::close);

        assertEquals(List.of("HTTP API", "modules", "database"), closed);
        assertEquals("One or more StarX resources failed to close", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("Unable to close modules", thrown.getSuppressed()[0].getMessage());
        assertSame(moduleFailure, thrown.getSuppressed()[0].getCause());
    }

    @Test
    void closeRetriesOnlyTheResourceThatFailed() throws Exception {
        PluginLifecycle lifecycle = new PluginLifecycle();
        List<String> closed = new ArrayList<>();
        int[] moduleAttempts = {0};
        lifecycle.start(() -> {
            lifecycle.own("database", () -> closed.add("database"));
            lifecycle.own("modules", () -> {
                closed.add("modules:" + ++moduleAttempts[0]);
                if (moduleAttempts[0] == 1) {
                    throw new IllegalStateException("module stop failed once");
                }
            });
            lifecycle.own("HTTP API", () -> closed.add("HTTP API"));
        });

        assertThrows(IllegalStateException.class, lifecycle::close);
        lifecycle.close();

        assertEquals(List.of("HTTP API", "modules:1", "database", "modules:2"), closed);
    }
}
