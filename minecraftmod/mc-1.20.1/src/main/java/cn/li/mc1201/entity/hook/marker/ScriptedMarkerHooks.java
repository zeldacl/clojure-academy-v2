package cn.li.mc1201.entity.hook.marker;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

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
