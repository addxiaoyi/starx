package io.github.addxiaoyi.starx.api.extension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry for managing auto-completers in StarX.
 * Allows extensions to register custom completers for various contexts.
 */
public interface StarxAutoCompleterRegistry {

    /**
     * Registers an auto-completer for a specific context.
     *
     * @param context the context identifier (e.g., "command", "config", "plugin")
     * @param completer the auto-completer to register
     */
    void register(String context, StarxAutoCompleter completer);

    /**
     * Registers an auto-completer with a supplier for lazy initialization.
     *
     * @param context the context identifier
     * @param supplier the supplier that creates the completer
     */
    default void register(String context, Supplier<StarxAutoCompleter> supplier) {
        register(context, supplier.get());
    }

    /**
     * Gets all registered completers for a context.
     *
     * @param context the context identifier
     * @return unmodifiable list of completers
     */
    List<StarxAutoCompleter> getCompleters(String context);

    /**
     * Gets completion suggestions for a context and input.
     *
     * @param context the context identifier
     * @param input the current input string
     * @return list of completion suggestions
     */
    List<String> complete(String context, String input);

    /**
     * Gets all registered contexts.
     *
     * @return set of context identifiers
     */
    java.util.Set<String> getContexts();

    /**
     * Default implementation of the auto-completer registry.
     */
    class DefaultRegistry implements StarxAutoCompleterRegistry {
        private final ConcurrentHashMap<String, List<StarxAutoCompleter>> completers = new ConcurrentHashMap<>();

        @Override
        public void register(String context, StarxAutoCompleter completer) {
            completers.compute(context, (k, v) -> {
                java.util.ArrayList<StarxAutoCompleter> list = v == null 
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v);
                list.add(completer);
                return List.copyOf(list);
            });
        }

        @Override
        public List<StarxAutoCompleter> getCompleters(String context) {
            return completers.getOrDefault(context, List.of());
        }

        @Override
        public List<String> complete(String context, String input) {
            List<String> results = new java.util.ArrayList<>();
            for (StarxAutoCompleter completer : getCompleters(context)) {
                if (completer != null) {
                    List<String> suggestions = completer.complete(input);
                    if (suggestions != null) {
                        results.addAll(suggestions);
                    }
                }
            }
            return results;
        }

        @Override
        public java.util.Set<String> getContexts() {
            return completers.keySet();
        }
    }
}