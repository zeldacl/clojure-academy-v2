package cn.li.mcbase.entity.hook.ray;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;
import java.util.function.Supplier;

public final class ScriptedRayHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedRayHooks.class;

    public static void register(String hookId, ScriptedRayHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedRayHook NOOP = new ScriptedRayHook() {
    };

    public static ScriptedRayHook resolve(String hookId) {
        ScriptedRayHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static void registerFactory(String implementationKey, Supplier<? extends ScriptedRayHook> factory) {
        AbstractHookRegistry.registerFactory(REGISTRY_CLASS, implementationKey, factory);
    }

    public static boolean registerByKey(String hookId, String implementationKey) {
        return AbstractHookRegistry.registerByKey(REGISTRY_CLASS, hookId, implementationKey);
    }
}
