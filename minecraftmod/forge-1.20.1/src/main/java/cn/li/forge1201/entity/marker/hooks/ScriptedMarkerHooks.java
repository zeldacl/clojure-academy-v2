package cn.li.forge1201.entity.marker.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Forge-specific registry for marker entity hooks.
 * Extends the shared AbstractHookRegistry with Forge hook types.
 * Maintains static API for backward compatibility.
 */
public final class ScriptedMarkerHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedMarkerHooks.class;
    private static final Class<? extends ScriptedMarkerHook> HOOK_INTERFACE = ScriptedMarkerHook.class;

    public static void register(String hookId, ScriptedMarkerHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static ScriptedMarkerHook resolve(String hookId) {
        ScriptedMarkerHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
