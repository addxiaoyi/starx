package io.github.addxiaoyi.starx.velocity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

final class PluginLifecycle implements AutoCloseable {
    private final Deque<Resource> resources = new ArrayDeque<>();
    private State state = State.NEW;

    synchronized void start(Startup startup) throws Exception {
        Objects.requireNonNull(startup, "startup");
        if (this.state != State.NEW) {
            throw new IllegalStateException("StarX lifecycle has already started");
        }

        this.state = State.STARTING;
        try {
            startup.run();
            this.state = State.STARTED;
        } catch (Exception error) {
            this.rollback(error);
            throw error;
        } catch (Error error) {
            this.rollback(error);
            throw error;
        }
    }

    synchronized Ownership own(String name, Runnable closeAction) {
        if (this.state != State.STARTING) {
            throw new IllegalStateException("StarX resources can only be registered during startup");
        }
        String resourceName = Objects.requireNonNull(name, "name").trim();
        if (resourceName.isEmpty()) {
            throw new IllegalArgumentException("Resource name cannot be blank");
        }

        Resource resource = new Resource(
            resourceName,
            Objects.requireNonNull(closeAction, "closeAction")
        );
        this.resources.addLast(resource);
        return () -> this.release(resource);
    }

    @Override
    public synchronized void close() {
        if (this.state == State.CLOSED) {
            return;
        }
        this.state = State.CLOSING;

        IllegalStateException failure = null;
        Deque<Resource> failedResources = new ArrayDeque<>();
        while (!this.resources.isEmpty()) {
            Resource resource = this.resources.removeLast();
            if (!resource.owned) {
                continue;
            }
            try {
                resource.closeAction.run();
            } catch (Throwable error) {
                failedResources.addFirst(resource);
                if (failure == null) {
                    failure = new IllegalStateException(
                        "One or more StarX resources failed to close"
                    );
                }
                failure.addSuppressed(new IllegalStateException(
                    "Unable to close " + resource.name,
                    error
                ));
            }
        }
        this.resources.addAll(failedResources);
        if (failure != null) {
            throw failure;
        }
        this.state = State.CLOSED;
    }

    private synchronized void release(Resource resource) {
        resource.owned = false;
    }

    private void rollback(Throwable startupFailure) {
        try {
            this.close();
        } catch (RuntimeException cleanupFailure) {
            startupFailure.addSuppressed(cleanupFailure);
        }
    }

    @FunctionalInterface
    interface Startup {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface Ownership {
        void release();
    }

    private enum State {
        NEW,
        STARTING,
        STARTED,
        CLOSING,
        CLOSED
    }

    private static final class Resource {
        private final String name;
        private final Runnable closeAction;
        private boolean owned = true;

        private Resource(String name, Runnable closeAction) {
            this.name = name;
            this.closeAction = closeAction;
        }
    }
}
