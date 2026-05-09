package cn.li.fabric1201.entity.effect.hooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricScriptedEffectHooks {
    private static final FabricScriptedEffectHook NOOP = (entity, level) -> {
    };
    private static final Map<String, FabricScriptedEffectHook> HOOKS = new ConcurrentHashMap<>();

    private FabricScriptedEffectHooks() {
    }

    public static void register(String hookId, FabricScriptedEffectHook hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        HOOKS.put(hookId, hook);
    }

    public static FabricScriptedEffectHook resolve(String hookId) {
        if (hookId == null || hookId.isEmpty()) {
            return NOOP;
        }
        return HOOKS.getOrDefault(hookId, NOOP);
    }

    public static boolean registerByClassName(String hookId, String className) {
        if (hookId == null || hookId.isEmpty() || className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class<?> rawClass = Class.forName(className);
            if (!FabricScriptedEffectHook.class.isAssignableFrom(rawClass)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends FabricScriptedEffectHook> hookClass = (Class<? extends FabricScriptedEffectHook>) rawClass;
            FabricScriptedEffectHook hook = hookClass.getDeclaredConstructor().newInstance();
            register(hookId, hook);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
