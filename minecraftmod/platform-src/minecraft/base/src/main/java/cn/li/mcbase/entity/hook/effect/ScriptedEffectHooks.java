package cn.li.mcbase.entity.hook.effect;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;
import java.util.function.Supplier;

public final class ScriptedEffectHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedEffectHooks.class;

    public static void register(String hookId, ScriptedEffectHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedEffectHook NOOP = new ScriptedEffectHook() {
    };

    public static ScriptedEffectHook resolve(String hookId) {
        ScriptedEffectHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static void registerFactory(String implementationKey, Supplier<? extends ScriptedEffectHook> factory) {
        AbstractHookRegistry.registerFactory(REGISTRY_CLASS, implementationKey, factory);
    }

    public static boolean registerByKey(String hookId, String implementationKey) {
        return AbstractHookRegistry.registerByKey(REGISTRY_CLASS, hookId, implementationKey);
    }
}
