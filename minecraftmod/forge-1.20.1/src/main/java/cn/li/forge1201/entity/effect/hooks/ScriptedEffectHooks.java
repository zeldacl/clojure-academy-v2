package cn.li.forge1201.entity.effect.hooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptedEffectHooks {
    private static final ScriptedEffectHook NOOP = (entity, level) -> {
    };
    private static final Map<String, ScriptedEffectHook> HOOKS = new ConcurrentHashMap<>();

    private ScriptedEffectHooks() {
    }

    public static void register(String hookId, ScriptedEffectHook hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        HOOKS.put(hookId, hook);
    }

    public static ScriptedEffectHook resolve(String hookId) {
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
            if (!ScriptedEffectHook.class.isAssignableFrom(rawClass)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ScriptedEffectHook> hookClass = (Class<? extends ScriptedEffectHook>) rawClass;
            ScriptedEffectHook hook = hookClass.getDeclaredConstructor().newInstance();
            register(hookId, hook);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
