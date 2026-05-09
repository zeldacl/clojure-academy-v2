package cn.li.fabric1201.entity.effect.hooks;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

/**
 * Fabric-specific registry for effect entity hooks.
 * Extends the shared AbstractHookRegistry with Fabric hook types.
 * Maintains static API for backward compatibility.
 */
public final class FabricScriptedEffectHooks {
    private static final Class<?> REGISTRY_CLASS = FabricScriptedEffectHooks.class;
    private static final Class<? extends FabricScriptedEffectHook> HOOK_INTERFACE = FabricScriptedEffectHook.class;

    public static void register(String hookId, FabricScriptedEffectHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    public static FabricScriptedEffectHook resolve(String hookId) {
        FabricScriptedEffectHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : (entity, level) -> {};
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
