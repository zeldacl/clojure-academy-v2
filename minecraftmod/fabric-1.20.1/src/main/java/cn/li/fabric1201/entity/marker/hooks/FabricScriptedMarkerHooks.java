package cn.li.fabric1201.entity.marker.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Fabric-specific registry for marker entity hooks.
 * Extends the shared AbstractHookRegistry with Fabric hook types.
 * Maintains static API for backward compatibility.
 */
public final class FabricScriptedMarkerHooks {
    private static final Class<?> REGISTRY_CLASS = FabricScriptedMarkerHooks.class;
    private static final Class<? extends FabricScriptedMarkerHook> HOOK_INTERFACE = FabricScriptedMarkerHook.class;

    public static void register(String hookId, FabricScriptedMarkerHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static FabricScriptedMarkerHook resolve(String hookId) {
        FabricScriptedMarkerHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
