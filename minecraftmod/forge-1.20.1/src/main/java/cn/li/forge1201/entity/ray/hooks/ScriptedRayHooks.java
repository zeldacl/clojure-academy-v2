package cn.li.forge1201.entity.ray.hooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptedRayHooks {
    private static final ScriptedRayHook NOOP = (entity, level) -> {
    };
    private static final Map<String, ScriptedRayHook> HOOKS = new ConcurrentHashMap<>();

    private ScriptedRayHooks() {
    }

    public static void register(String hookId, ScriptedRayHook hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        HOOKS.put(hookId, hook);
    }

    public static ScriptedRayHook resolve(String hookId) {
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
            if (!ScriptedRayHook.class.isAssignableFrom(rawClass)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ScriptedRayHook> hookClass = (Class<? extends ScriptedRayHook>) rawClass;
            ScriptedRayHook hook = hookClass.getDeclaredConstructor().newInstance();
            register(hookId, hook);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
