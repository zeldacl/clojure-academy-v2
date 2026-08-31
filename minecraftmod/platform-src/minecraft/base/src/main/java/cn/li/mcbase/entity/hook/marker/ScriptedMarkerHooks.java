package cn.li.mcbase.entity.hook.marker;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;
import java.util.function.Supplier;

public final class ScriptedMarkerHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedMarkerHooks.class;

    public static void register(String hookId, ScriptedMarkerHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedMarkerHook NOOP = new ScriptedMarkerHook() {
    };

    public static ScriptedMarkerHook resolve(String hookId) {
        ScriptedMarkerHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static void registerFactory(String implementationKey, Supplier<? extends ScriptedMarkerHook> factory) {
        AbstractHookRegistry.registerFactory(REGISTRY_CLASS, implementationKey, factory);
    }

    public static boolean registerByKey(String hookId, String implementationKey) {
        return AbstractHookRegistry.registerByKey(REGISTRY_CLASS, hookId, implementationKey);
    }
}
