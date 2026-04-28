package cn.li.forge1201.entity.marker.hooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptedMarkerHooks {
    private static final ScriptedMarkerHook NOOP = (entity, level) -> {
    };
    private static final Map<String, ScriptedMarkerHook> HOOKS = new ConcurrentHashMap<>();

    private ScriptedMarkerHooks() {
    }

    public static void register(String hookId, ScriptedMarkerHook hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        HOOKS.put(hookId, hook);
    }

    public static ScriptedMarkerHook resolve(String hookId) {
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
            if (!ScriptedMarkerHook.class.isAssignableFrom(rawClass)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ScriptedMarkerHook> hookClass = (Class<? extends ScriptedMarkerHook>) rawClass;
            ScriptedMarkerHook hook = hookClass.getDeclaredConstructor().newInstance();
            register(hookId, hook);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
