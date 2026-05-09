package cn.li.forge1201.entity.ray.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Forge-specific registry for ray entity hooks.
 * Extends the shared AbstractHookRegistry with Forge hook types.
 * Maintains static API for backward compatibility.
 */
public final class ScriptedRayHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedRayHooks.class;
    private static final Class<? extends ScriptedRayHook> HOOK_INTERFACE = ScriptedRayHook.class;

    public static void register(String hookId, ScriptedRayHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static ScriptedRayHook resolve(String hookId) {
        ScriptedRayHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
