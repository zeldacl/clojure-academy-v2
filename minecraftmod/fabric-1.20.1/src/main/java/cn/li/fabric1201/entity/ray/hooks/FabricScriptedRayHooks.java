package cn.li.fabric1201.entity.ray.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Fabric-specific registry for ray entity hooks.
 * Extends the shared AbstractHookRegistry with Fabric hook types.
 * Maintains static API for backward compatibility.
 */
public final class FabricScriptedRayHooks {
    private static final Class<?> REGISTRY_CLASS = FabricScriptedRayHooks.class;
    private static final Class<? extends FabricScriptedRayHook> HOOK_INTERFACE = FabricScriptedRayHook.class;

    public static void register(String hookId, FabricScriptedRayHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static FabricScriptedRayHook resolve(String hookId) {
        FabricScriptedRayHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
