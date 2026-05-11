package cn.li.mc1201.entity.hook.effect;

import cn.li.mc1201.entity.hook.AbstractHookRegistry;

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
