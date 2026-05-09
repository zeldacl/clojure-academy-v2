package cn.li.forge1201.entity.effect.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Forge-specific registry for effect entity hooks.
 * Extends the shared AbstractHookRegistry with Forge hook types.
 * Maintains static API for backward compatibility.
 */
public final class ScriptedEffectHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedEffectHooks.class;
    private static final Class<? extends ScriptedEffectHook> HOOK_INTERFACE = ScriptedEffectHook.class;

    public static void register(String hookId, ScriptedEffectHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static ScriptedEffectHook resolve(String hookId) {
        ScriptedEffectHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
