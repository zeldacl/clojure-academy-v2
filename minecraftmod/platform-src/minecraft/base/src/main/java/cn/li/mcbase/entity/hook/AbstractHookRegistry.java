package cn.li.mcbase.entity.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Generic hook registry for entity-specific hooks.
 * Hook factories are registered during bootstrap; tick/render paths only do a
 * direct concurrent-map lookup and never load classes or allocate adapters.
 */
public final class AbstractHookRegistry {
    private static final Map<Class<?>, Map<String, ?>> REGISTRIES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Supplier<?>>> FACTORIES = new ConcurrentHashMap<>();

    private AbstractHookRegistry() {
    }

    /** Register a hook implementation by hook ID. */
    public static <T> void register(Class<?> registryClass, String hookId, T hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, T> hooks = (Map<String, T>) (Object) getRegistryMap(registryClass);
        hooks.put(hookId, hook);
    }

    /** Resolve a hook by hook ID. */
    public static <T> T resolve(Class<?> registryClass, String hookId) {
        if (hookId == null || hookId.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, T> hooks = (Map<String, T>) (Object) getRegistryMap(registryClass);
        return hooks.get(hookId);
    }

    /** Register a startup-only factory under a stable implementation key. */
    public static <T> void registerFactory(Class<?> registryClass, String implementationKey,
                                           Supplier<? extends T> factory) {
        if (implementationKey == null || implementationKey.isEmpty() || factory == null) {
            return;
        }
        getFactoryMap(registryClass).put(implementationKey, factory);
    }

    /** Instantiate a pre-registered factory during bootstrap, without reflection. */
    public static <T> boolean registerByKey(Class<?> registryClass, String hookId,
                                            String implementationKey) {
        if (hookId == null || hookId.isEmpty() || implementationKey == null || implementationKey.isEmpty()) {
            return false;
        }
        Supplier<?> factory = getFactoryMap(registryClass).get(implementationKey);
        if (factory == null) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            T hook = (T) factory.get();
            if (hook == null) {
                return false;
            }
            register(registryClass, hookId, hook);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Map<String, ?> getRegistryMap(Class<?> registryClass) {
        return REGISTRIES.computeIfAbsent(registryClass, clz -> new ConcurrentHashMap<>());
    }

    private static Map<String, Supplier<?>> getFactoryMap(Class<?> registryClass) {
        return FACTORIES.computeIfAbsent(registryClass, clz -> new ConcurrentHashMap<>());
    }
}